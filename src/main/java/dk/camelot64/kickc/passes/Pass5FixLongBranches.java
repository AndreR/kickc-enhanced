package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import kickass.KickAssembler65CE02;
import kickass.nonasm.c64.CharToPetsciiConverter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identify and fix long branches (over 128 bytes).
 * Requires KickAssembler in CLASS path
 */
public class Pass5FixLongBranches extends Pass5AsmOptimization {

   private static class BranchToFix {
      final AsmChunk chunk;
      final AsmInstruction instr;

      BranchToFix(AsmChunk chunk, AsmInstruction instr) {
         this.chunk = chunk;
         this.instr = instr;
      }
   }

   public Pass5FixLongBranches(Program program) {
      super(program);
   }

   public boolean optimize() {

      // Make sure KickAssembler is present
      try {
         Class.forName("kickass.KickAssembler", false, getClass().getClassLoader());
      } catch(ClassNotFoundException e) {
         getLog().append("Warning! KickAssembler not found in java CLASSPATH. Cannot eliminate long branches.");
      }

      // Pre-pass: analytically find and fix long branches in memory
      long tPreStart = System.currentTimeMillis();
      int preFixedCount = preFixLongBranches();
      long tPreElapsed = System.currentTimeMillis() - tPreStart;
      if(preFixedCount > 0) {
         System.out.println(String.format("      [PASS5 PRE-FIX LONG BRANCHES] fixed %d branches in %4.2f s", preFixedCount, tPreElapsed / 1000.0));
      }

      // Perform repeated optimization until no more branches are too long
      boolean anyOptimized = (preFixedCount > 0);
      boolean stepOptimized = true;
      int iter = 0;
      while(stepOptimized) {
         iter++;
         long tStepStart = System.currentTimeMillis();
         stepOptimized = step();
         long tStepElapsed = System.currentTimeMillis() - tStepStart;
         System.out.println(String.format("      [PASS5 LONG BRANCH iter %d] took %6.2f s (fixed=%b)", iter, tStepElapsed / 1000.0, stepOptimized));
         if(stepOptimized) anyOptimized = true;
      }
      return anyOptimized;
   }

   /**
    * Pre-pass to analytically detect and fix long branches across all scopes.
    *
    * @return total number of branches fixed
    */
   private int preFixLongBranches() {
      int totalFixed = 0;
      boolean anyFixedThisPass = true;
      int pass = 0;

      while(anyFixedThisPass) {
         anyFixedThisPass = false;
         pass++;

         // Phase 1: Collect label offsets per scope
         Map<String, Integer> labelOffsets = new HashMap<>();
         String currentScope = null;
         boolean inCode = true;
         int currentOffset = 0;

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
                  inCode = true;
                  currentOffset = 0;
               } else if(line instanceof AsmScopeEnd) {
                  currentScope = null;
                  inCode = true;
                  currentOffset = 0;
               } else if(currentScope != null) {
                  if(line instanceof AsmSegment) {
                     String seg = ((AsmSegment) line).getName();
                     if(seg != null && seg.toLowerCase().contains("data")) {
                        inCode = false;
                     } else {
                        inCode = true;
                     }
                  } else if(inCode) {
                     if(line instanceof AsmLabel) {
                        String label = ((AsmLabel) line).getLabel();
                        if(!label.startsWith("!")) {
                           labelOffsets.put(currentScope + "::" + label, currentOffset);
                        }
                     }
                     currentOffset += line.getLineBytes();
                  }
               }
            }
         }

         // Phase 2: Check conditional branches and record long ones
         currentScope = null;
         inCode = true;
         currentOffset = 0;
         List<BranchToFix> branchesToFix = new ArrayList<>();

         for(AsmChunk chunk : getAsmProgram().getChunks()) {
            for(AsmLine line : chunk.getLines()) {
               if(line instanceof AsmScopeBegin) {
                  currentScope = ((AsmScopeBegin) line).getLabel();
                  inCode = true;
                  currentOffset = 0;
               } else if(line instanceof AsmScopeEnd) {
                  currentScope = null;
                  inCode = true;
                  currentOffset = 0;
               } else if(currentScope != null) {
                  if(line instanceof AsmSegment) {
                     String seg = ((AsmSegment) line).getName();
                     if(seg != null && seg.toLowerCase().contains("data")) {
                        inCode = false;
                     } else {
                        inCode = true;
                     }
                  } else if(inCode) {
                     if(line instanceof AsmInstruction) {
                        AsmInstruction instr = (AsmInstruction) line;
                        CpuOpcode inverse = invertBranch(instr.getCpuOpcode());
                        if(inverse != null) {
                           String target = instr.getOperandJumpTarget();
                           if(target != null && !target.startsWith("!")) {
                              Integer targetOffset = labelOffsets.get(currentScope + "::" + target);
                              if(targetOffset != null) {
                                 int branchPc = currentOffset + 2;
                                 int distance = targetOffset - branchPc;
                                 if(distance < -128 || distance > 127) {
                                    branchesToFix.add(new BranchToFix(chunk, instr));
                                 }
                              }
                           }
                        }
                     }
                     currentOffset += line.getLineBytes();
                  }
               }
            }
         }

         for(BranchToFix b : branchesToFix) {
            if(fixLongBranch(b.chunk, b.instr)) {
               anyFixedThisPass = true;
               totalFixed++;
            }
         }
      }

      return totalFixed;
   }

   /**
    * Detect if any branch distance is to long, and fix it by rewriting the branch if it is.
    *
    * @return true if any branch was rewritten
    */
   private boolean step() {
      // Reindex ASM lines
      new Pass5ReindexAsmLines(getProgram()).optimize();
      long t0 = System.currentTimeMillis();
      Path tmpDir = TmpDirManager.MANAGER.newTmpDir();
      // Generate the ASM file
      try {
         final Path asmFilePath = getProgram().getOutputFileManager().getOutputFile(tmpDir, getProgram().getOutputFileManager().getAsmExtension());
         writeOutputFile(asmFilePath, getProgram().getAsm().toString(new AsmProgram.AsmPrintState(false), null));
         // Copy Resource Files
         for(Path asmResourceFile : getProgram().getAsmResourceFiles()) {
            File binFile = getTmpFile(tmpDir, asmResourceFile.getFileName().toString());
            Files.copy(asmResourceFile, binFile.toPath());
         }
      } catch(IOException e) {
         throw new CompileError("Error writing ASM temp file.", e);
      }
      long tWrite = System.currentTimeMillis() - t0;

      // Compile using KickAssembler - catch the output in a String
      long t1 = System.currentTimeMillis();
      File asmFile = getProgram().getOutputFileManager().getOutputFile(tmpDir, getProgram().getOutputFileManager().getAsmExtension()).toFile();
      File binaryFile = getProgram().getOutputFileManager().getOutputFile(tmpDir, getProgram().getOutputFileManager().getBinaryExtension()).toFile();
      ByteArrayOutputStream kickAssOut = new ByteArrayOutputStream();
      System.setOut(new PrintStream(kickAssOut));
      int asmRes = -1;
      try {
         CharToPetsciiConverter.setCurrentEncoding("screencode_mixed");
         asmRes = KickAssembler65CE02.main2(new String[]{asmFile.getAbsolutePath(), "-o", binaryFile.getAbsolutePath()});
      } catch(Throwable e) {
         if(e instanceof AssertionError && e.getMessage().contains("Invalid number of bytes in memblock!")) {
            throw new CompileError("Error! KickAssembler failed due to assertion. Please run java without -ea / -enableassertions.", e);
         } else {
            throw new CompileError("Error! KickAssembler failed, while trying to fix long branch. " + e);
         }
      } finally {
         System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
      }
      String output = kickAssOut.toString();
      long tKasm = System.currentTimeMillis() - t1;
      System.out.println(String.format("         (writeAsm=%4.2fs, kasm=%4.2fs, res=%d)", tWrite / 1000.0, tKasm / 1000.0, asmRes));

      if(getLog().isVerboseFixLongBranch())
         getLog().append("Pass5FixLongBranches: ASM process result " + asmRes);

      // Look for a long branch distance error
      if(asmRes != 0) {
         String outputLines[] = output.split("\\r?\\n");
         List<Integer> linesToFix = new ArrayList<>();
         for(int i = 0; i < outputLines.length; i++) {
            String outputLine = outputLines[i];
            if(outputLine.contains("Error: relative address is illegal (jump distance is too far).")) {
               // Found a long branch!
               if(i + 1 < outputLines.length) {
                  String contextLine = outputLines[i + 1];
                  Pattern contextPattern = Pattern.compile("at line ([0-9]*),.*");
                  Matcher matcher = contextPattern.matcher(contextLine);
                  if(matcher.matches()) {
                     int contextLineIdx = Integer.parseInt(matcher.group(1));
                     linesToFix.add(contextLineIdx - 1);
                  }
               }
            }
         }
         if(!linesToFix.isEmpty()) {
            // Fix in descending order to avoid line offset invalidation
            linesToFix.sort(Collections.reverseOrder());
            boolean fixedAny = false;
            for(int lineIdx : linesToFix) {
               if(fixLongBranch(lineIdx)) {
                  fixedAny = true;
               }
            }
            if(fixedAny) {
               return true;
            }
         }
         // KickAssembler returned error, but no long branch was found. Fail with the output from KickAss
         throw new CompileError("Error! Failed to compile using KickAss\n" + output);
      }
      return false;
   }

   /**
    * Fix a long branch on a specific instruction within a chunk.
    */
   private boolean fixLongBranch(AsmChunk asmChunk, AsmInstruction asmInstruction) {
      CpuOpcode cpuOpcode = asmInstruction.getCpuOpcode();
      CpuOpcode inverseCpuOpcode = invertBranch(cpuOpcode);
      if(inverseCpuOpcode != null) {
         if(getLog().isVerboseFixLongBranch()) {
            getLog().append("Fixing long branch [" + asmInstruction.getIndex() + "] " + asmInstruction.toString() + " to " + inverseCpuOpcode.getMnemonic());
         }
         String branchDest = asmInstruction.getOperandJumpTarget();
         asmInstruction.setCpuOpcode(inverseCpuOpcode);
         String newLabel = AsmFormat.asmFix("!" + branchDest);
         asmInstruction.setOperandJumpTarget(newLabel + "+");
         CpuOpcode jmpOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("jmp", CpuAddressingMode.ABS, false);
         AsmInstruction jmpInstruction = new AsmInstruction(jmpOpcode, branchDest, null, null);
         asmChunk.addLineAfter(asmInstruction, jmpInstruction);
         asmChunk.addLineAfter(jmpInstruction, new AsmLabel(newLabel));
         return true;
      }
      return false;
   }

   /**
    * Fix a long branch detected at a specific ASM index
    *
    * @param idx The index of the ASM line with the long branch
    * @return True if the branch was fixed
    */
   private boolean fixLongBranch(int idx) {
      AsmProgram asm = getProgram().getAsm();
      AsmChunk asmChunk = asm.getAsmChunk(idx);
      if(asmChunk != null) {
         AsmLine asmLine = asmChunk.getAsmLine(idx);
         if(asmLine instanceof AsmInstruction) {
            return fixLongBranch(asmChunk, (AsmInstruction) asmLine);
         }
      }
      return false;
   }

   private CpuOpcode invertBranch(CpuOpcode cpuOpcode) {
      switch(cpuOpcode.getMnemonic()) {
         case "bcc":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bcs", CpuAddressingMode.REL, false);
         case "bcs":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bcc", CpuAddressingMode.REL, false);
         case "beq":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bne", CpuAddressingMode.REL, false);
         case "bne":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("beq", CpuAddressingMode.REL, false);
         case "bpl":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bmi", CpuAddressingMode.REL, false);
         case "bmi":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bpl", CpuAddressingMode.REL, false);
         case "bvs":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bvc", CpuAddressingMode.REL, false);
         case "bvc":
            return getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("bvs", CpuAddressingMode.REL, false);
         default:
            return null;
      }
   }

   private File writeOutputFile(Path outputFile, String outputString) throws IOException {
      File file = outputFile.toFile();
      FileOutputStream outputStream = new FileOutputStream(file);
      OutputStreamWriter writer = new OutputStreamWriter(outputStream);
      writer.write(outputString);
      writer.close();
      outputStream.close();
      return file;
   }

   private static File getTmpFile(Path tmpDir, String fileName) {
      return new File(tmpDir.toFile(), fileName);
   }

}