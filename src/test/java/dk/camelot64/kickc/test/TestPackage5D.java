package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass4RegisterUpliftCombinations;
import dk.camelot64.kickc.passes.Pass5ARegisterReturnOptimization;
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
 * Tests for Package 5D: GEN-04, GEN-05, GEN-06.
 */
public class TestPackage5D {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_5d_", ".c");
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
   void testGen04_ByteGranularDeadStoreEliminationRemovesUnreadHighByte() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      AsmChunk chunk = asm.startChunk(ScopeRef.ROOT, null, "test");
      chunk.addLine(new AsmScopeBegin("test"));
      chunk.addLine(new AsmLabelDecl("__1", "$10"));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$02", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "__1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "0", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "__1+1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "__1", null, null));
      chunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      chunk.addLine(new AsmScopeEnd());

      assertTrue(new Pass5DeadStoreElimination(program).optimize());
      String out = asm.toString();
      assertFalse(out.contains("sta.z __1+1"),
            "Unread high byte sta.z __1+1 should be eliminated even though __1+0 is read! ASM:\n" + out);
      assertTrue(out.contains("sta.z __1"), "Live low byte sta.z __1 must be preserved! ASM:\n" + out);
   }

   @Test
   void testGen05_InterruptHandlerCompilesCleanlyThroughPass4AndPass5() throws Exception {
      var r1 = new Pass4RegisterUpliftCombinations.UpliftTrialResult(null, 100, 12, 1, true);
      var r2 = new Pass4RegisterUpliftCombinations.UpliftTrialResult(null, 100, 10, 2, true);
      assertEquals(12, r1.bytes);
      assertEquals(10, r2.bytes);
      assertEquals(2, r2.upliftedRegCount);

      String code = """
            #include <stdint.h>
            volatile uint8_t tick = 0;
            volatile uint8_t acc = 0;
            volatile uint8_t * const VIC_IRQ = (uint8_t *)0xD019;

            __interrupt(hardware_all) void raster_isr(void) {
                tick++;
                acc += tick;
                *VIC_IRQ = 1;
            }

            void main(void) {
                *((void (**)())0x0314) = &raster_isr;
                while(1) {}
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("raster_isr:"), "Expected raster_isr label in ASM:\n" + asm);
      assertTrue(asm.contains("rti"), "Expected rti in interrupt handler ASM:\n" + asm);
      assertTrue(asm.contains("$d019"), "Expected VIC_IRQ acknowledge in ISR ASM:\n" + asm);
   }

   @Test
   void testGen06_AutoARegisterReturnCompilesCleanly() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t g_x = 5;
            volatile uint8_t g_y = 7;
            volatile uint8_t g_out1, g_out2;

            uint8_t add_vals(uint8_t a, uint8_t b) {
                return a + b + 1;
            }

            void main(void) {
                g_out1 = add_vals(g_x, g_y);
                g_out2 = add_vals(g_y, g_x);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertNotNull(asm);
      assertTrue(asm.contains("jsr add_vals"), "Expected calls to add_vals:\n" + asm);
      assertFalse(asm.contains("lda.z add_vals.return"),
            "8-bit return value should be returned directly in A, not reloaded from add_vals.return! ASM:\n" + asm);
   }

   @Test
   void testGen06_CoalescedCallerZpVariableBlocksARegisterReturnOptimization() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      Procedure callee = new Procedure("callee", new SymbolTypeProcedure(SymbolType.BYTE, Collections.emptyList()), program.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      program.getScope().add(callee);
      Variable retVar = new Variable("return", Variable.Kind.PHI_MASTER, SymbolType.BYTE, callee, Variable.MemoryArea.ZEROPAGE_MEMORY, "Data", null);
      retVar.setAllocation(new Registers.RegisterZpMem(2, 1, false));
      callee.add(retVar);

      Procedure caller = new Procedure("caller", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()), program.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      program.getScope().add(caller);

      // Callee: sta.z return; rts
      AsmChunk calleeChunk = asm.startChunk(callee.getRef(), null, "callee");
      calleeChunk.addLine(new AsmScopeBegin("callee"));
      calleeChunk.addLine(new AsmLabelDecl("return", "2"));
      calleeChunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "42", null, null));
      calleeChunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      calleeChunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      calleeChunk.addLine(new AsmScopeEnd());

      // Caller: one direct load site + one coalesced site (.label x = 2; jsr callee; lda #1; sta $0400; lda.z x; sta $0401; rts)
      AsmChunk callerChunk = asm.startChunk(caller.getRef(), null, "caller");
      callerChunk.addLine(new AsmScopeBegin("caller"));
      callerChunk.addLine(new AsmLabelDecl("x", "2"));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "callee", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "callee.return", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "$0402", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "callee", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "$0400", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "x", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABS, false), "$0401", null, null));
      callerChunk.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      callerChunk.addLine(new AsmScopeEnd());

      assertFalse(new Pass5ARegisterReturnOptimization(program).optimize(),
            "GEN-06 must NOT eliminate sta.z return when caller later reads coalesced ZP slot $02 via .label x = 2!");
      assertTrue(asm.toString().contains("sta.z return"), "sta.z return must remain intact! ASM:\n" + asm);
   }
}
