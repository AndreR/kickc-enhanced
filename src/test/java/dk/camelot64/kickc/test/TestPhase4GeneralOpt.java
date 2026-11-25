package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass5DeadStoreElimination;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression and verification tests for general Phase 4 compiler optimizations (OPT-01..OPT-06).
 */
public class TestPhase4GeneralOpt {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_phase4_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         Program program = compiler.getProgram();
         program.getIncludePaths().add("src/main/kc/include");
         program.getLibraryPaths().add("src/main/kc/lib");
         program.getTargetPlatformPaths().add("src/main/kc/target");
         program.setAsmFragmentBaseFolder(Paths.get("src/main/fragment"));
         program.initAsmFragmentMasterSynthesizer(null);

         File platformFile = new File("src/main/kc/target/c64.tgt");
         TargetPlatform targetPlatform = CTargetPlatformParser.parseTargetPlatformFile(
               TargetPlatform.DEFAULT_NAME, platformFile, tempFile, program.getTargetPlatformPaths());
         program.setTargetPlatform(targetPlatform);
         program.addReservedZps(targetPlatform.getReservedZps());
         program.getOutputFileManager().setBinaryExtension(targetPlatform.getOutFileExtension());
         program.getOutputFileManager().setCurrentDir(Paths.get("."));
         program.getOutputFileManager().setPrimaryCFile(tempFile);

         Map<String, String> defines = new HashMap<>();
         defines.put("__KICKC__", "1");
         defines.putAll(targetPlatform.getDefines());

         compiler.compile(Collections.singletonList(tempFile), defines);
         return program;
      } finally {
         Files.deleteIfExists(tempFile);
      }
   }

   @Test
   void testOpt01_IntraBlockOverwriteDsePositive() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "fn");
      chunk.addLine(new AsmScopeBegin("fn"));
      chunk.addLine(new AsmLabelDecl("cnt", "$10"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "cnt", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "cnt", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "cnt", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      Pass5DeadStoreElimination pass = new Pass5DeadStoreElimination(program);
      assertTrue(pass.optimize(), "OPT-01 should eliminate overwritten store and dead load");

      String out = asm.toString();
      assertFalse(out.contains("lda #0"), "Dead immediate load before overwritten store should be removed:\n" + out);
      assertTrue(out.contains("lda #1"), "Second store load must remain:\n" + out);
      int storeCount = out.split("sta\\.z cnt|sta cnt", -1).length - 1;
      assertEquals(1, storeCount, "Only one store to cnt should remain:\n" + out);
   }

   @Test
   void testOpt01_IntraBlockOverwriteDseGuardsHardwareAndReads() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "fn");
      chunk.addLine(new AsmScopeBegin("fn"));
      chunk.addLine(new AsmLabelDecl("hw", "$d020"));
      chunk.addLine(new AsmLabelDecl("x", "$12"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "hw", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "hw", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "2", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "x", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IZY, false), "($fe)", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "3", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "x", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      Pass5DeadStoreElimination pass = new Pass5DeadStoreElimination(program);
      assertFalse(pass.optimize(), "OPT-01 must NOT eliminate hardware-address stores or stores across indirect reads");
   }

   @Test
   void testOpt02_NonVoidCallerToVoidCalleeTailCall() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "volatile uint8_t g_acc;\n"
            + "void step(uint8_t v) { g_acc += v; }\n"
            + "uint32_t eval(uint32_t x) {\n"
            + "   uint32_t r = x + 1;\n"
            + "   step(1);\n"
            + "   step(2);\n"
            + "   return r;\n"
            + "}\n"
            + "volatile uint32_t g_res;\n"
            + "void main(void) { g_res = eval(10); g_res += eval(20); }\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      int evalIdx = asm.indexOf("eval:");
      assertTrue(evalIdx >= 0, "eval procedure missing:\n" + asm);
      String evalBody = asm.substring(evalIdx, asm.indexOf("}", evalIdx));
      assertTrue(evalBody.contains("jmp step"), "OPT-02 should tail-call optimize final jsr step; rts to jmp step:\n" + evalBody);
   }

   @Test
   void testOpt02_NonVoidCallerClobberedReturnNotTailCalled() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "volatile uint8_t g_out;\n"
            + "void clobber(uint8_t v) { g_out = v + 1; }\n"
            + "uint8_t keep_and_call(uint8_t x) {\n"
            + "   uint8_t r = x + 5;\n"
            + "   clobber(x);\n"
            + "   return r;\n"
            + "}\n"
            + "void main(void) { g_out = keep_and_call(3); g_out += keep_and_call(4); }\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      assertTrue(asm.contains("keep_and_call:"), "keep_and_call procedure missing");
   }
}
