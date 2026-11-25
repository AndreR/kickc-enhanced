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
import dk.camelot64.kickc.passes.Pass5StandardPeepholeOptimization;
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
            + "void step(uint8_t v) {\n"
            + "   if(v & 1) g_acc += v;\n"
            + "   if(v & 2) g_acc += v;\n"
            + "   if(v & 4) g_acc += v;\n"
            + "}\n"
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

   @Test
   void testOpt03_RedundantClcElimination() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "fn");
      chunk.addLine(new AsmScopeBegin("fn"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("and", CpuAddressingMode.IMM, false), "3", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("asl", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("asl", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("adc", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("adc", CpuAddressingMode.IMM, false), "2", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      Pass5StandardPeepholeOptimization pass = new Pass5StandardPeepholeOptimization(program);
      assertTrue(pass.optimize(), "OPT-03 should eliminate redundant clc instructions when C=0 is guaranteed");
      String out = asm.toString();
      assertFalse(out.contains("clc"), "Both redundant clc instructions should be eliminated:\n" + out);
   }

   @Test
   void testOpt03_UnsafeClcPreserved() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "fn");
      chunk.addLine(new AsmScopeBegin("fn"));
      chunk.addLine(new AsmLabelDecl("v", "$10"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("adc", CpuAddressingMode.ZP, false), "v", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("clc", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("adc", CpuAddressingMode.IMM, false), "1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      Pass5StandardPeepholeOptimization pass = new Pass5StandardPeepholeOptimization(program);
      assertFalse(pass.optimize(), "OPT-03 must preserve clc after unbounded adc");
   }

   @Test
   void testOpt04_LoopRegisterPinningInNonVoidLeafFunction() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "uint8_t table[256];\n"
            + "uint8_t g_idx;\n"
            + "uint8_t scan_down(uint8_t x, uint8_t h) {\n"
            + "   g_idx = (h << 4) + x;\n"
            + "   uint8_t row = h;\n"
            + "   while(table[g_idx] != 0) {\n"
            + "      g_idx -= 16;\n"
            + "      row--;\n"
            + "      if(row == 0) return 0;\n"
            + "   }\n"
            + "   return row;\n"
            + "}\n"
            + "volatile uint8_t g_out;\n"
            + "void main(void) {\n"
            + "   g_out = scan_down(2, 6);\n"
            + "   g_out += scan_down(3, 5);\n"
            + "}\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      int fnIdx = asm.indexOf("scan_down:");
      assertTrue(fnIdx >= 0, "scan_down missing:\n" + asm);
      String fnBody = asm.substring(fnIdx, asm.indexOf("}", fnIdx));
      assertTrue(fnBody.contains("table,x"), "OPT-04 should pin g_idx in X and index table,x:\n" + fnBody);
      assertFalse(fnBody.contains("ldy.z g_idx"), "OPT-04 should eliminate ldy.z g_idx inside loop:\n" + fnBody);
   }

   @Test
   void testOpt05_RangeNarrowingSignedDiff() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "volatile uint8_t g_a, g_b;\n"
            + "volatile int16_t g_out;\n"
            + "void main(void) {\n"
            + "   uint8_t a = g_a & 0x0f;\n"
            + "   uint8_t b = g_b & 0x0f;\n"
            + "   int16_t d = (int16_t)a - (int16_t)b;\n"
            + "   if(d < 0) d = -d;\n"
            + "   g_out = d;\n"
            + "}\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      assertTrue(asm.contains("sec"), "OPT-05 should compile cleanly:\n" + asm);
      // With 8-bit narrowing, the initial difference should not need 16-bit sbc #0 on high byte
      int mainIdx = asm.indexOf("main:");
      String mainBody = asm.substring(mainIdx, asm.indexOf("}", mainIdx));
      assertFalse(mainBody.contains("sbc #0"), "OPT-05 should narrow (int16_t)a - (int16_t)b to 8-bit subtraction without high-byte sbc #0:\n" + mainBody);
   }

   @Test
   void testOpt05_RangeNarrowingPreservesUnbounded16Bit() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "volatile uint8_t g_a, g_b;\n"
            + "volatile int16_t g_out;\n"
            + "void main(void) {\n"
            + "   uint8_t a = g_a;\n"
            + "   uint8_t b = g_b;\n"
            + "   int16_t d = (int16_t)a - (int16_t)b;\n"
            + "   g_out = d;\n"
            + "}\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      int mainIdx = asm.indexOf("main:");
      String mainBody = asm.substring(mainIdx, asm.indexOf("}", mainIdx));
      assertTrue(mainBody.contains("sbc.z __2+1") || mainBody.contains("sbc #0"), "OPT-05 must keep 16-bit subtraction for unbounded uint8_t operands:\n" + mainBody);
   }

   @Test
   void testOpt06_AutoInlineHotLeafWithConstArg() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "volatile uint8_t g_acc;\n"
            + "void add_scaled(uint8_t x, uint8_t scale) {\n"
            + "   g_acc += x + scale;\n"
            + "}\n"
            + "void main(void) {\n"
            + "   add_scaled(g_acc, 1);\n"
            + "   add_scaled(g_acc, 2);\n"
            + "}\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      assertFalse(asm.contains("jsr add_scaled"), "OPT-06 should auto-inline small hot-leaf helper with constant args:\n" + asm);
   }

   @Test
   void testOpt06_DoesNotInlineLargeOrRecursiveProcedures() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "volatile uint8_t g_v;\n"
            + "uint8_t rec(uint8_t n) {\n"
            + "   if(n == 0) return 1;\n"
            + "   return n + rec(n - 1);\n"
            + "}\n"
            + "void main(void) {\n"
            + "   g_v = rec(4);\n"
            + "}\n";
      Program prog = compileC(src);
      String asm = prog.getAsm().toString();
      assertTrue(asm.contains("jsr rec"), "OPT-06 must NOT inline recursive procedures:\n" + asm);
   }
}
