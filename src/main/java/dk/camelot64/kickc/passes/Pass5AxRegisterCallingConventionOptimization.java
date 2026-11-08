package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;

import java.util.*;

/**
 * 16-Bit Hardware Register Pair AX for Calling Conventions (__ax / Auto-AX ABI).
 *
 * In 6502 assembly, passing and returning 16-bit values (words, ints, pointers)
 * in the AX register pair (A = low byte, X = high byte) eliminates Zero-Page and memory
 * allocation for function returns and parameters.
 */
public class Pass5AxRegisterCallingConventionOptimization extends Pass5AsmOptimization {

   public static class LineRef {
      final int index;
      final AsmChunk chunk;
      final AsmLine line;
      final String scope;

      LineRef(int index, AsmChunk chunk, AsmLine line, String scope) {
         this.index = index;
         this.chunk = chunk;
         this.line = line;
         this.scope = scope;
      }
   }

   private boolean performed = false;

   public Pass5AxRegisterCallingConventionOptimization(Program program) {
      super(program);
   }

   private List<LineRef> buildAllLines() {
      List<LineRef> allLines = new ArrayList<>();
      int lineIdx = 0;
      String currentScope = null;
      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         String chunkScope = chunk.getScopeLabel();
         if(chunkScope != null) currentScope = chunkScope;
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
            }
            allLines.add(new LineRef(lineIdx++, chunk, line, currentScope));
            if(line instanceof AsmScopeEnd) {
               currentScope = null;
            }
         }
      }
      return allLines;
   }

   private static int prevInstIdx(List<LineRef> allLines, int startIdx) {
      for(int i = startIdx; i >= 0; i--) {
         if(allLines.get(i).line instanceof AsmInstruction) {
            return i;
         }
      }
      return -1;
   }

   private static int nextInstIdx(List<LineRef> allLines, int startIdx) {
      for(int i = startIdx; i < allLines.size(); i++) {
         if(allLines.get(i).line instanceof AsmInstruction) {
            return i;
         }
      }
      return -1;
   }

   private static String stripPlus1(String op) {
      if(op == null) return null;
      String t = op.trim();
      if(t.endsWith("+1")) {
         return t.substring(0, t.length() - 2).trim();
      }
      return null;
   }

   private static boolean matchesVarLo(String operand, String varName, String varAddr, Map<String, String> aliases) {
      if(operand == null) return false;
      String t = operand.trim();
      if(varName != null && t.equals(varName)) return true;
      if(varAddr != null && t.equals(varAddr)) return true;
      if(aliases != null) {
         String resolved = aliases.get(t);
         if(resolved != null) {
            if(varName != null && resolved.equals(varName)) return true;
            if(varAddr != null && resolved.equals(varAddr)) return true;
         }
      }
      return false;
   }

   private static boolean matchesVarHi(String operand, String varName, String varAddr, Map<String, String> aliases) {
      if(operand == null) return false;
      String stripped = stripPlus1(operand);
      if(stripped != null) {
         return matchesVarLo(stripped, varName, varAddr, aliases);
      }
      try {
         if(varAddr != null) {
            int addr = Integer.parseInt(varAddr);
            int opAddr = Integer.parseInt(operand.trim());
            if(opAddr == addr + 1) return true;
         }
      } catch(NumberFormatException ignored) {}
      return false;
   }

   private static Long parseConstValue(String val) {
      if(val == null) return null;
      String t = val.trim();
      try {
         if(t.startsWith("$")) {
            return Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            return Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            return Long.parseLong(t.substring(1), 2);
         } else {
            return Long.parseLong(t);
         }
      } catch(NumberFormatException e) {
         return null;
      }
   }

   @Override
   public boolean optimize() {
      if(performed) {
         return false;
      }
      performed = true;

      boolean overallModified = false;
      boolean passModified = true;

      CpuOpcode taxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("tax", CpuAddressingMode.NON, false);
      CpuOpcode ldxImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", CpuAddressingMode.IMM, false);
      CpuOpcode ldaImm = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", CpuAddressingMode.IMM, false);

      Set<AsmInstruction> transformedRts = new HashSet<>();
      Set<String> paramInitializedProcs = new HashSet<>();

      while(passModified) {
         passModified = false;
         List<LineRef> allLines = buildAllLines();

         // Scope-level label declarations / aliases: Map<Scope, Map<LabelName, Value>>
         Map<String, Map<String, String>> scopeAliases = new HashMap<>();
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmLabelDecl decl && ref.scope != null) {
               scopeAliases.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(decl.getName(), decl.getValue());
            }
         }

         // Collect procedures eligible for AX return and AX parameter
         Set<String> axReturnProcs = new HashSet<>();
         Map<String, String> procReturnVarAddr = new HashMap<>();
         Set<String> axParamProcs = new HashSet<>();
         Map<String, String> procParamVarName = new HashMap<>();
         Map<String, String> procParamVarAddr = new HashMap<>();

         Scope rootScope = getProgram().getScope();
         for(Procedure proc : rootScope.getAllProcedures(true)) {
            String name = proc.getLocalName();
            if(proc.getInterruptType() != null || proc.isDeclaredIntrinsic()) {
               continue;
            }
            if(Procedure.CallingConvention.STACK_CALL.equals(proc.getCallingConvention())) {
               continue;
            }

            boolean returns16 = proc.getReturnType() != null && proc.getReturnType().getSizeBytes() == 2;
            if(proc.isAxConvention() || returns16) {
               axReturnProcs.add(name);
               Map<String, String> aliases = scopeAliases.get(name);
               if(aliases != null && aliases.containsKey("return")) {
                  procReturnVarAddr.put(name, aliases.get("return"));
               }
            }

            if(proc.getParameters().size() == 1) {
               Variable param0 = proc.getParameters().get(0);
               if(param0.getType() != null && param0.getType().getSizeBytes() == 2) {
                     axParamProcs.add(name);
                     String pName = param0.getLocalName();
                     procParamVarName.put(name, pName);
                     Map<String, String> aliases = scopeAliases.get(name);
                     if(aliases != null && aliases.containsKey(pName)) {
                        procParamVarAddr.put(name, aliases.get(pName));
                     }
               }
            }
         }

         // -------------------------------------------------------------
         // Step 1: Callee AX Return Optimization
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.scope != null && axReturnProcs.contains(ref.scope)) {
               if(ref.line instanceof AsmInstruction inst && "rts".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
                  if(transformedRts.contains(inst)) {
                     continue;
                  }

                  String retAddr = procReturnVarAddr.get(ref.scope);
                  Map<String, String> aliases = scopeAliases.get(ref.scope);

                  int idx1 = prevInstIdx(allLines, i - 1);
                  boolean inSameScope = (idx1 >= 0 && ref.scope.equals(allLines.get(idx1).scope));

                  // Already returns in AX?
                  if(inSameScope) {
                     LineRef ref1 = allLines.get(idx1);
                     if(ref1.line instanceof AsmInstruction inst1) {
                        String mn1 = inst1.getCpuOpcode().getMnemonic().toLowerCase();
                        if("ldx".equals(mn1)) {
                           transformedRts.add(inst);
                           continue;
                        }
                        int idx0 = prevInstIdx(allLines, idx1 - 1);
                        if(idx0 >= 0 && ref.scope.equals(allLines.get(idx0).scope)) {
                           AsmInstruction inst0 = (AsmInstruction) allLines.get(idx0).line;
                           String mn0 = inst0.getCpuOpcode().getMnemonic().toLowerCase();
                           if("tax".equals(mn0) && "lda".equals(mn1)) {
                              transformedRts.add(inst);
                              continue;
                           }
                        }
                     }
                  }

                  // Case 1A: Constant return defined in .label return = <const> with no instructions before RTS
                  String retValDecl = (aliases != null) ? aliases.get("return") : null;
                  Long constVal = parseConstValue(retValDecl);
                  if(constVal != null && (!inSameScope || !(allLines.get(idx1).line instanceof AsmInstruction))) {
                     int rtsIndex = ref.chunk.getLines().indexOf(inst);
                     if(rtsIndex >= 0) {
                        AsmInstruction ldaRet = new AsmInstruction(ldaImm, "<" + retValDecl, null, null);
                        ldaRet.setNoOptimize(true);
                        AsmInstruction ldxRet = new AsmInstruction(ldxImm, ">" + retValDecl, null, null);
                        ldxRet.setNoOptimize(true);
                        ref.chunk.getLines().add(rtsIndex, ldaRet);
                        ref.chunk.getLines().add(rtsIndex + 1, ldxRet);
                        transformedRts.add(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }

                  if(!inSameScope) {
                     transformedRts.add(inst);
                     continue;
                  }
                  LineRef ref1 = allLines.get(idx1);
                  if(!(ref1.line instanceof AsmInstruction inst1)) {
                     transformedRts.add(inst);
                     continue;
                  }

                  // Case 1B: Constant return sequence:
                  // lda #<const ; sta return ; lda #>const ; sta return+1 ; rts
                  if("sta".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                     matchesVarHi(inst1.getOperand1(), "return", retAddr, aliases)) {

                     int idx2 = prevInstIdx(allLines, idx1 - 1);
                     int idx3 = (idx2 >= 0) ? prevInstIdx(allLines, idx2 - 1) : -1;
                     int idx4 = (idx3 >= 0) ? prevInstIdx(allLines, idx3 - 1) : -1;

                     if(idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                        AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                        AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                        AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                        if("lda".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                           inst2.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM) &&
                           "sta".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                           matchesVarLo(inst3.getOperand1(), "return", retAddr, aliases) &&
                           "lda".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic()) &&
                           inst4.getCpuOpcode().getAddressingMode().equals(CpuAddressingMode.IMM)) {

                           inst2.setCpuOpcode(ldxImm);
                           inst2.setNoOptimize(true);
                           inst4.setNoOptimize(true);
                           allLines.get(idx3).chunk.getLines().remove(inst3);
                           ref1.chunk.getLines().remove(inst1);
                           transformedRts.add(inst);
                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }

                     // Case 1C: Dynamic return ending in sta return+1:
                     // Insert: tax ; lda.z return
                     CpuAddressingMode ldaMode = inst1.getCpuOpcode().getAddressingMode();
                     CpuOpcode ldaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", ldaMode, false);
                     String retLoOp = (retAddr != null) ? retAddr : "return";
                     int rtsIndex = ref.chunk.getLines().indexOf(inst);
                     if(rtsIndex >= 0) {
                        AsmInstruction taxInst = new AsmInstruction(taxOp, null, null, null);
                        taxInst.setNoOptimize(true);
                        AsmInstruction ldaRet = new AsmInstruction(ldaOp, retLoOp, null, null);
                        ldaRet.setNoOptimize(true);
                        ref.chunk.getLines().add(rtsIndex, taxInst);
                        ref.chunk.getLines().add(rtsIndex + 1, ldaRet);
                        transformedRts.add(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  } else {
                     // Case 1D: Any other return (e.g. rol.z return+1; rts):
                     // Insert: lda.z return ; ldx.z return+1
                     CpuAddressingMode ldaMode = (retAddr != null) ? CpuAddressingMode.ZP : CpuAddressingMode.ABS;
                     CpuOpcode ldaOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("lda", ldaMode, false);
                     CpuOpcode ldxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", ldaMode, false);
                     String retLoOp = (retAddr != null) ? retAddr : "return";
                     String retHiOp = (retAddr != null) ? retAddr + "+1" : "return+1";
                     int rtsIndex = ref.chunk.getLines().indexOf(inst);
                     if(rtsIndex >= 0) {
                        AsmInstruction ldaRet = new AsmInstruction(ldaOp, retLoOp, null, null);
                        ldaRet.setNoOptimize(true);
                        AsmInstruction ldxRet = new AsmInstruction(ldxOp, retHiOp, null, null);
                        ldxRet.setNoOptimize(true);
                        ref.chunk.getLines().add(rtsIndex, ldaRet);
                        ref.chunk.getLines().add(rtsIndex + 1, ldxRet);
                        transformedRts.add(inst);
                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }
               }
            }
         }

         if(passModified) continue;

         // -------------------------------------------------------------
         // Step 2: Callee Parameter Receiving in AX
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.scope != null && axParamProcs.contains(ref.scope) && ref.line instanceof AsmInstruction) {
               int prev = prevInstIdx(allLines, i - 1);
               if(prev >= 0 && ref.scope.equals(allLines.get(prev).scope)) {
                  continue; // Not the first instruction in scope
               }

               if(paramInitializedProcs.contains(ref.scope)) {
                  continue;
               }

               String pName = procParamVarName.get(ref.scope);
               String pAddr = procParamVarAddr.get(ref.scope);
               Map<String, String> calleeAliases = scopeAliases.get(ref.scope);

               int idx1 = i;
               int idx2 = nextInstIdx(allLines, idx1 + 1);
               int idx3 = (idx2 >= 0) ? nextInstIdx(allLines, idx2 + 1) : -1;
               int idx4 = (idx3 >= 0) ? nextInstIdx(allLines, idx3 + 1) : -1;

               boolean matchedDirect = false;
               if(idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                  AsmInstruction inst1 = (AsmInstruction) allLines.get(idx1).line;
                  AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                  AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                  AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                  if("lda".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                     matchesVarLo(inst1.getOperand1(), pName, pAddr, calleeAliases) &&
                     "sta".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                     "lda".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                     matchesVarHi(inst3.getOperand1(), pName, pAddr, calleeAliases) &&
                     "sta".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic())) {

                     allLines.get(idx1).chunk.getLines().remove(inst1);
                     allLines.get(idx3).chunk.getLines().remove(inst3);
                     CpuAddressingMode stMode = inst4.getCpuOpcode().getAddressingMode();
                     CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                     inst4.setCpuOpcode(stxOp);
                     inst2.setNoOptimize(true);
                     inst4.setNoOptimize(true);

                     matchedDirect = true;
                     paramInitializedProcs.add(ref.scope);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }

               if(!matchedDirect) {
                  CpuAddressingMode stMode = (pAddr != null) ? CpuAddressingMode.ZP : CpuAddressingMode.ABS;
                  CpuOpcode staOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", stMode, false);
                  CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                  String pLoOp = (pAddr != null) ? pAddr : pName;
                  String pHiOp = (pAddr != null) ? pAddr + "+1" : pName + "+1";

                  int entryIndex = ref.chunk.getLines().indexOf(ref.line);
                  if(entryIndex >= 0) {
                     AsmInstruction staInst = new AsmInstruction(staOp, pLoOp, null, null);
                     staInst.setNoOptimize(true);
                     AsmInstruction stxInst = new AsmInstruction(stxOp, pHiOp, null, null);
                     stxInst.setNoOptimize(true);
                     ref.chunk.getLines().add(entryIndex, staInst);
                     ref.chunk.getLines().add(entryIndex + 1, stxInst);
                     paramInitializedProcs.add(ref.scope);
                     passModified = true;
                     overallModified = true;
                     break;
                  }
               }
            }
         }

         if(passModified) continue;

         // -------------------------------------------------------------
         // Step 3: Caller Parameter Passing in AX (1st 16-bit param)
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmInstruction inst) {
               String mn = inst.getCpuOpcode().getMnemonic().toLowerCase();
               if("jsr".equals(mn) || "jmp".equals(mn)) {
                  String target = inst.getOperand1();
                  if(target != null && axParamProcs.contains(target.trim())) {
                     String procName = target.trim();
                     String pName = procParamVarName.get(procName);
                     String pAddr = procParamVarAddr.get(procName);
                     Map<String, String> callerAliases = scopeAliases.get(ref.scope);

                     int idx1 = prevInstIdx(allLines, i - 1);
                     int idx2 = (idx1 >= 0) ? prevInstIdx(allLines, idx1 - 1) : -1;
                     int idx3 = (idx2 >= 0) ? prevInstIdx(allLines, idx2 - 1) : -1;
                     int idx4 = (idx3 >= 0) ? prevInstIdx(allLines, idx3 - 1) : -1;

                     if(idx1 >= 0 && idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                        AsmInstruction inst1 = (AsmInstruction) allLines.get(idx1).line;
                        AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                        AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                        AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                        String qualifiedParam = procName + "." + pName;
                        if("sta".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                           matchesVarHi(inst1.getOperand1(), qualifiedParam, pAddr, callerAliases) &&
                           "lda".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                           "sta".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                           matchesVarLo(inst3.getOperand1(), qualifiedParam, pAddr, callerAliases) &&
                           "lda".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic())) {

                           allLines.get(idx1).chunk.getLines().remove(inst1);
                           CpuAddressingMode ldaMode = inst2.getCpuOpcode().getAddressingMode();
                           CpuOpcode ldxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("ldx", ldaMode, false);
                           inst2.setCpuOpcode(ldxOp);
                           inst4.setNoOptimize(true);
                           inst2.setNoOptimize(true);
                           allLines.get(idx3).chunk.getLines().remove(inst3);

                           passModified = true;
                           overallModified = true;
                           break;
                        }
                     }
                  }
               }
            }
         }

         if(passModified) continue;

         // Step 4: Caller AX Return Consumption
         // -------------------------------------------------------------
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmInstruction inst && "jsr".equalsIgnoreCase(inst.getCpuOpcode().getMnemonic())) {
               String target = inst.getOperand1();
               if(target != null && axReturnProcs.contains(target.trim())) {
                  String procName = target.trim();
                  String retAddr = procReturnVarAddr.get(procName);
                  Map<String, String> callerAliases = scopeAliases.get(ref.scope);

                  int idx1 = nextInstIdx(allLines, i + 1);
                  int idx2 = (idx1 >= 0) ? nextInstIdx(allLines, idx1 + 1) : -1;
                  int idx3 = (idx2 >= 0) ? nextInstIdx(allLines, idx2 + 1) : -1;
                  int idx4 = (idx3 >= 0) ? nextInstIdx(allLines, idx3 + 1) : -1;

                  if(idx1 >= 0 && idx2 >= 0 && idx3 >= 0 && idx4 >= 0) {
                     AsmInstruction inst1 = (AsmInstruction) allLines.get(idx1).line;
                     AsmInstruction inst2 = (AsmInstruction) allLines.get(idx2).line;
                     AsmInstruction inst3 = (AsmInstruction) allLines.get(idx3).line;
                     AsmInstruction inst4 = (AsmInstruction) allLines.get(idx4).line;

                     // Case 4A: Storing return value to destination
                     if("lda".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                        matchesVarLo(inst1.getOperand1(), procName + ".return", retAddr, callerAliases) &&
                        "sta".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic()) &&
                        "lda".equalsIgnoreCase(inst3.getCpuOpcode().getMnemonic()) &&
                        matchesVarHi(inst3.getOperand1(), procName + ".return", retAddr, callerAliases) &&
                        "sta".equalsIgnoreCase(inst4.getCpuOpcode().getMnemonic())) {

                        allLines.get(idx1).chunk.getLines().remove(inst1);
                        allLines.get(idx3).chunk.getLines().remove(inst3);
                        CpuAddressingMode stMode = inst4.getCpuOpcode().getAddressingMode();
                        CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                        inst4.setCpuOpcode(stxOp);
                        inst2.setNoOptimize(true);
                        inst4.setNoOptimize(true);

                        passModified = true;
                        overallModified = true;
                        break;
                     }
                  }

                  // Case 4B: Caller tests or uses return value directly from memory without storing
                  if(idx1 >= 0 && allLines.get(idx1).line instanceof AsmInstruction inst1) {
                     if("lda".equalsIgnoreCase(inst1.getCpuOpcode().getMnemonic()) &&
                        matchesVarLo(inst1.getOperand1(), procName + ".return", retAddr, callerAliases)) {
                        if(idx2 >= 0 && allLines.get(idx2).line instanceof AsmInstruction inst2 &&
                           !"sta".equalsIgnoreCase(inst2.getCpuOpcode().getMnemonic())) {
                           CpuAddressingMode stMode = inst1.getCpuOpcode().getAddressingMode();
                           CpuOpcode staOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("sta", stMode, false);
                           CpuOpcode stxOp = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("stx", stMode, false);
                           String retLoOp = inst1.getOperand1();
                           String retHiOp = retLoOp + "+1";

                           int jsrIndex = ref.chunk.getLines().indexOf(inst);
                           if(jsrIndex >= 0) {
                              AsmInstruction staInst = new AsmInstruction(staOp, retLoOp, null, null);
                              staInst.setNoOptimize(true);
                              AsmInstruction stxInst = new AsmInstruction(stxOp, retHiOp, null, null);
                              stxInst.setNoOptimize(true);
                              ref.chunk.getLines().add(jsrIndex + 1, staInst);
                              ref.chunk.getLines().add(jsrIndex + 2, stxInst);
                              passModified = true;
                              overallModified = true;
                              break;
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return overallModified;
   }
}
