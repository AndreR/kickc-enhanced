package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.passes.Pass5PointerReloadElimination;
import dk.camelot64.kickc.passes.Pass5RedundantStoreAndReloadElimination;
import dk.camelot64.kickc.passes.Pass5UnnecesaryLoadElimination;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for SSA-01 (volatile & hardware I/O safety in Pass 5)
 * and FL-01 (bit-accurate {N, Z, C, V} backward flag liveness).
 */
public class TestPass5VolatileAndFlags {

   private static AsmInstruction inst(AsmProgram asm, String mnem, CpuAddressingMode mode, String op1) {
      CpuOpcode opcode = asm.getTargetCpu().getCpu65xx().getOpcode(mnem, mode, false);
      assertNotNull(opcode, "Opcode not found: " + mnem + " " + mode);
      return new AsmInstruction(opcode, op1, null, null);
   }

   private static Program createProgram() {
      Program program = new Program();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);
      return program;
   }

   private static List<String> collectInstructions(AsmProgram asm) {
      List<String> out = new ArrayList<>();
      for(AsmChunk chunk : asm.getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmInstruction) {
               out.add(line.getAsm().trim());
            }
         }
      }
      return out;
   }

   @Test
   void testSsa01HardwareClearOnReadPreserved() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "hw_read");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ABS, "$dc0d"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "tmp1"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ABS, "$dc0d"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "tmp2"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();
      new Pass5RedundantStoreAndReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      long dc0dReads = insns.stream().filter(s -> s.equals("lda $dc0d")).count();
      assertEquals(2, dc0dReads, "Both clear-on-read loads from $dc0d must be preserved, got: " + insns);
   }

   @Test
   void testSsa01HardwareWriteThenReadPreserved() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "hw_wr_rd");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "$7f"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "$dc0d"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ABS, "$dc0d"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "tmp1"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();
      new Pass5RedundantStoreAndReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertTrue(insns.contains("lda $dc0d"), "Read-after-write to hardware register $dc0d must be preserved, got: " + insns);
   }

   @Test
   void testSsa01AliasedHardwareIrqAckPreserved() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "hw_ack");
      asm.addLine(new AsmScopeBegin("irq"));
      asm.addLine(new AsmLabelDecl("REG", "$d019"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ABS, "REG"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "REG"));
      asm.addLine(inst(asm, "rti", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5RedundantStoreAndReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertTrue(insns.contains("sta REG"), "Aliased IRQ acknowledge store to $d019 must be preserved, got: " + insns);
   }

   @Test
   void testSsa01CpuPort0001Preserved() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "cpu_port");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "$01"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "$01"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "$01"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "tmp1"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();
      new Pass5RedundantStoreAndReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      long reads = insns.stream().filter(s -> s.equals("lda.z $01")).count();
      long writes = insns.stream().filter(s -> s.equals("sta.z $01")).count();
      assertEquals(2, reads, "Both loads from CPU port $01 must be preserved, got: " + insns);
      assertEquals(1, writes, "Store to CPU port $01 must be preserved, got: " + insns);
   }

   @Test
   void testSsa01VolatileRamVariablePreserved() {
      Program program = createProgram();
      Variable volVar = new Variable(
         "irq_flag",
         Variable.Kind.LOAD_STORE,
         SymbolType.BYTE.getQualified(true, false),
         program.getScope(),
         Variable.MemoryArea.MAIN_MEMORY,
         null,
         null
      );
      volVar.setAsmName("irq_flag");
      program.getScope().add(volVar);

      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "vol_var");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ABS, "irq_flag"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "tmp1"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ABS, "irq_flag"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ABS, "irq_flag"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();
      new Pass5RedundantStoreAndReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      long reads = insns.stream().filter(s -> s.equals("lda irq_flag")).count();
      long writes = insns.stream().filter(s -> s.equals("sta irq_flag")).count();
      assertEquals(2, reads, "Both loads of volatile variable irq_flag must be preserved, got: " + insns);
      assertEquals(1, writes, "Store to volatile variable irq_flag must be preserved, got: " + insns);
   }

   @Test
   void testSsa01PointerToVolatileDerefPreserved() {
      Program program = createProgram();
      Procedure proc = new Procedure("sample_io", new dk.camelot64.kickc.model.types.SymbolTypeProcedure(SymbolType.BYTE, new ArrayList<>()), program.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, dk.camelot64.kickc.model.symbols.Bank.COMMON);
      program.getScope().add(proc);
      Variable regPtr = new Variable(
         "reg_ptr",
         Variable.Kind.LOAD_STORE,
         new SymbolTypePointer(SymbolType.BYTE.getQualified(true, false)),
         proc,
         Variable.MemoryArea.ZEROPAGE_MEMORY,
         null,
         null
      );
      regPtr.setAsmName("reg_ptr");
      proc.add(regPtr);

      AsmProgram asm = program.getAsm();
      asm.startChunk(proc.getRef(), 0, "vol_ptr");
      asm.addLine(new AsmScopeBegin("sample_io"));
      asm.addLine(inst(asm, "ldy", CpuAddressingMode.ZP, "reg_ptr"));
      asm.addLine(inst(asm, "sty", CpuAddressingMode.ZP, "$fe"));
      asm.addLine(inst(asm, "ldy", CpuAddressingMode.ZP, "reg_ptr+1"));
      asm.addLine(inst(asm, "sty", CpuAddressingMode.ZP, "$ff"));
      asm.addLine(inst(asm, "ldy", CpuAddressingMode.IMM, "0"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IZY, "$fe"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "c"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IZY, "$fe"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "d"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5PointerReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      long derefs = insns.stream().filter(s -> s.equals("lda ($fe),y")).count();
      assertEquals(2, derefs, "Both indirect dereferences of volatile pointer reg_ptr must be preserved, got: " + insns);
   }

   // =========================================================================
   // FL-01: Bit-Accurate {N, Z, C, V} Backward Flag Liveness Tests
   // =========================================================================

   @Test
   void testFl01DeadClcBeforeAslEliminated() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "dead_clc");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "clc", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "idx"));
      asm.addLine(inst(asm, "asl", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "offset"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertFalse(insns.contains("clc"), "Dead CLC before ASL (overwrites C) must be eliminated, got: " + insns);
   }

   @Test
   void testFl01LiveClcBeforeAdcPreserved() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "live_clc");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "clc", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "idx"));
      asm.addLine(inst(asm, "adc", CpuAddressingMode.IMM, "1"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "offset"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertTrue(insns.contains("clc"), "Live CLC before ADC must be preserved, got: " + insns);
   }

   @Test
   void testFl01SecBeforeCarryReturnRtsPreserved() {
      Program program = createProgram();
      Procedure proc = new Procedure("check_flag", new dk.camelot64.kickc.model.types.SymbolTypeProcedure(SymbolType.BOOLEAN, new ArrayList<>()), program.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, dk.camelot64.kickc.model.symbols.Bank.COMMON);
      proc.setCarryReturn(true);
      program.getScope().add(proc);

      AsmProgram asm = program.getAsm();
      asm.startChunk(proc.getRef(), 0, "carry_ret");
      asm.addLine(new AsmScopeBegin("check_flag"));
      asm.addLine(inst(asm, "sec", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertTrue(insns.contains("sec"), "SEC before carry-return RTS must be preserved, got: " + insns);
   }

   @Test
   void testFl01DeadSecBeforeNormalRtsEliminated() {
      Program program = createProgram();
      Procedure proc = new Procedure("normal_proc", new dk.camelot64.kickc.model.types.SymbolTypeProcedure(SymbolType.VOID, new ArrayList<>()), program.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, dk.camelot64.kickc.model.symbols.Bank.COMMON);
      program.getScope().add(proc);

      AsmProgram asm = program.getAsm();
      asm.startChunk(proc.getRef(), 0, "normal_ret");
      asm.addLine(new AsmScopeBegin("normal_proc"));
      asm.addLine(inst(asm, "sec", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertFalse(insns.contains("sec"), "Dead SEC before non-carry-return RTS must be eliminated, got: " + insns);
   }

   @Test
   void testFl01RedundantLoadBeforeCarryBranchEliminated() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "load_before_bcc");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "val"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "val"));
      asm.addLine(inst(asm, "bcc", CpuAddressingMode.REL, "skip"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "0"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "out"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmLabel("skip"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "1"));
      asm.addLine(inst(asm, "sta", CpuAddressingMode.ZP, "out"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5RedundantStoreAndReloadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertFalse(insns.contains("lda.z val"), "Redundant lda.z val before BCC (which only reads C, not N/Z) must be eliminated, got: " + insns);
   }

   @Test
   void testFl01DeadCmpEliminatedButHardwareCmpPreserved() {
      Program program = createProgram();
      AsmProgram asm = program.getAsm();
      asm.startChunk(ScopeRef.ROOT, 0, "dead_cmp");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "cmp", CpuAddressingMode.IMM, "5"));
      asm.addLine(inst(asm, "cmp", CpuAddressingMode.ABS, "$dc0d"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.IMM, "0"));
      asm.addLine(inst(asm, "asl", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      new Pass5UnnecesaryLoadElimination(program).optimize();

      List<String> insns = collectInstructions(asm);
      assertFalse(insns.contains("cmp #5"), "Dead CMP #5 must be eliminated, got: " + insns);
      assertTrue(insns.contains("cmp $dc0d"), "Hardware I/O CMP $dc0d must be preserved even if flags are dead, got: " + insns);
   }

   @Test
   void testTd4StandardPeepholeRunsWithoutUnodc() {
      Program program = new Program();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502);
      program.setAsm(asm);
      asm.startChunk(ScopeRef.ROOT, 0, "std_peephole");
      asm.addLine(new AsmScopeBegin("main"));
      asm.addLine(inst(asm, "lda", CpuAddressingMode.ZP, "x"));
      asm.addLine(inst(asm, "cmp", CpuAddressingMode.IMM, "0"));
      asm.addLine(inst(asm, "beq", CpuAddressingMode.REL, "done"));
      asm.addLine(inst(asm, "tax", CpuAddressingMode.NON, null));
      asm.addLine(inst(asm, "txa", CpuAddressingMode.NON, null));
      asm.addLine(new AsmLabel("done"));
      asm.addLine(inst(asm, "rts", CpuAddressingMode.NON, null));
      asm.addLine(new AsmScopeEnd());

      boolean modified = new dk.camelot64.kickc.passes.Pass5StandardPeepholeOptimization(program).optimize();
      assertTrue(modified, "Standard 6502 peepholes must run on official MOS6502 without UNODC");

      List<String> insns = collectInstructions(asm);
      assertFalse(insns.contains("cmp #0"), "Redundant CMP #0 after LDA must be eliminated on official 6502, got: " + insns);
      assertFalse(insns.contains("txa"), "Redundant TXA after TAX must be eliminated on official 6502, got: " + insns);
   }
}
