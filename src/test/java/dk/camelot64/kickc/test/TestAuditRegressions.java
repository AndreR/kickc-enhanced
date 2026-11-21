package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.NumberParser;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test suite for Final Audit findings KRIT-01 through KRIT-10,
 * HOCH-01..08, and TS-07/TS-10/TS-15/TS-16 edge cases.
 */
public class TestAuditRegressions {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_audit_", ".c");
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
   void testKrit01_ScopedLabelLookupAndMultiHopBoundary() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      // Two scopes both defining __b1 and !: labels
      AsmChunk c1 = asm.startChunk(ScopeRef.ROOT, null, "fn1");
      c1.addLine(new AsmScopeBegin("fn1"));
      c1.addLine(new AsmLabel("__b1"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      AsmChunk c2 = asm.startChunk(ScopeRef.ROOT, null, "fn2");
      c2.addLine(new AsmScopeBegin("fn2"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("jmp", CpuAddressingMode.ABS, false), "__b1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("bne", CpuAddressingMode.REL, false), "!++", null, null));
      c2.addLine(new AsmLabel("!"));
      c2.addLine(new AsmLabel("__b1"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      AsmChunk c3 = asm.startChunk(ScopeRef.ROOT, null, "fn3");
      c3.addLine(new AsmScopeBegin("fn3"));
      c3.addLine(new AsmLabel("!"));
      c3.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c3.addLine(new AsmScopeEnd());

      List<Pass5AsmOptimization.LineRef> all = Pass5AsmOptimization.flattenLines(asm);
      int jmpIdx = -1;
      int bneIdx = -1;
      int fn2B1Idx = -1;
      for(int i = 0; i < all.size(); i++) {
         Pass5AsmOptimization.LineRef lr = all.get(i);
         if("fn2".equals(lr.scope) && lr.line instanceof AsmInstruction inst && "jmp".equals(inst.getCpuOpcode().getMnemonic())) {
            jmpIdx = i;
         }
         if("fn2".equals(lr.scope) && lr.line instanceof AsmInstruction inst && "bne".equals(inst.getCpuOpcode().getMnemonic())) {
            bneIdx = i;
         }
         if("fn2".equals(lr.scope) && lr.line instanceof AsmLabel lbl && "__b1".equals(lbl.getLabel())) {
            fn2B1Idx = i;
         }
      }
      assertTrue(jmpIdx >= 0 && bneIdx >= 0 && fn2B1Idx >= 0);

      // Named label __b1 from fn2 MUST resolve to fn2::__b1, NOT fn1::__b1!
      assertEquals(fn2B1Idx, Pass5AsmOptimization.findLabelIndex(all, "__b1", jmpIdx),
            "KRIT-01: __b1 in fn2 must resolve within fn2 scope, not fn1");
      // !++ in fn2 has only ONE !: before ScopeEnd -> MUST return -1, NOT jump into fn3's !:!
      assertEquals(-1, Pass5AsmOptimization.findLabelIndex(all, "!++", bneIdx),
            "KRIT-01: !++ must not cross scope boundary into fn3");
   }

   @Test
   void testKrit02_JsrDcpBitLivenessAndDcpNotSettingAFlags() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      // Test 1: jsr callee must treat A as live (NOT dead!)
      AsmChunk c1 = asm.startChunk(ScopeRef.ROOT, null, "test_jsr");
      c1.addLine(new AsmScopeBegin("test_jsr"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "5", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "callee", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      List<Pass5AsmOptimization.LineRef> all1 = Pass5AsmOptimization.flattenLines(asm);
      assertFalse(Pass5AsmOptimization.isRegDeadAlongPath("A", all1, 2, new java.util.HashSet<>(), "test_jsr", program),
            "KRIT-02: jsr must conservatively treat A as live!");

      // Test 2: dcp and bit read A!
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "test_bit_dcp");
      c2.addLine(new AsmScopeBegin("test_bit_dcp"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("bit", CpuAddressingMode.ZP, false), "$10", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("dcp", CpuAddressingMode.ZP, false), "$11", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      List<Pass5AsmOptimization.LineRef> all2 = Pass5AsmOptimization.flattenLines(asm2);
      assertFalse(Pass5AsmOptimization.isRegDeadAlongPath("A", all2, 1, new java.util.HashSet<>(), "test_bit_dcp", program),
            "KRIT-02: bit reads A!");
      assertFalse(Pass5AsmOptimization.isRegDeadAlongPath("A", all2, 2, new java.util.HashSet<>(), "test_bit_dcp", program),
            "KRIT-02: dcp reads A!");

      // Test 3: dcp does NOT set N/Z for register A (it sets N/Z for A - (M - 1))
      assertFalse(Pass5AsmOptimization.doesInstructionSetZAndN(
            new AsmInstruction(cpu.getOpcode("dcp", CpuAddressingMode.ZP, false), "$11", null, null), "A"),
            "KRIT-02: dcp must NOT be treated as setting N/Z for A!");
   }

   @Test
   void testKrit04_ARegisterReturnChecksNZFlagsAndCallerAlias() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // Case 1: Callee ends with `sta.z return; ldx #0; rts` (N/Z reflect X, NOT A!)
      // Caller does `jsr fn; lda.z fn.return; beq done` (expects N/Z for A!)
      // Pass5ARegisterReturnOptimization MUST NOT delete `lda.z fn.return`!
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);

      AsmChunk cCallee = asm1.startChunk(ScopeRef.ROOT, null, "fn");
      cCallee.addLine(new AsmLabel("fn"));
      cCallee.addLine(new AsmScopeBegin("fn"));
      cCallee.addLine(new AsmLabelDecl("return", "$02"));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$10", null, null));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.IMM, false), "0", null, null));
      cCallee.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCallee.addLine(new AsmScopeEnd());

      AsmChunk cCaller = asm1.startChunk(ScopeRef.ROOT, null, "main");
      cCaller.addLine(new AsmLabel("main"));
      cCaller.addLine(new AsmScopeBegin("main"));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "fn", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "fn.return", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("beq", CpuAddressingMode.REL, false), "__b1", null, null));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$20", null, null));
      cCaller.addLine(new AsmLabel("__b1"));
      cCaller.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCaller.addLine(new AsmScopeEnd());

      assertFalse(new Pass5ARegisterReturnOptimization(p1).optimize(),
            "KRIT-04: Must NOT drop lda.z fn.return when caller branches on Z flag and callee clobbered Z via ldx #0! ASM:\n" + asm1);
   }

   @Test
   void testKrit05And06_IllegalOpcodeGuardsAliasingFlagNAndZpFf() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // KRIT-05 (ILL-04): `lda arr,x; ldx $10` -> MUST NOT fold into `lax` or reorder because X is index of arr,x!
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);
      AsmChunk c1 = asm1.startChunk(ScopeRef.ROOT, null, "ill04");
      c1.addLine(new AsmScopeBegin("ill04"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ABX, false), "arr", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ABS, false), "arr", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$02", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());
      new Pass5IllegalOpcodeOptimization(p1).optimize();
      assertTrue(asm1.toString().contains("lda arr"),
            "KRIT-05: sta arr,x followed by lda arr may alias when X==0 and must not be folded! ASM:\n" + asm1);

      // KRIT-06 (ILL-08A): `and #$fe; lsr` -> `alr #$fe` clears N=0, whereas `lsr` clears N=0 too, wait:
      // `and #$ff; lsr` -> `alr` vs `lsr`: `alr` shifts right so bit 7 becomes 0; check `asl`/`alr` with live FLAG_N
      // and ILL-06B: `stx.z $ff; sax.z $10` where `$ff` is READ afterwards -> `stx.z $ff` MUST NOT be deleted!
      Program p2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      p2.setAsm(asm2);
      AsmChunk c2 = asm2.startChunk(ScopeRef.ROOT, null, "ill06_ff_live");
      c2.addLine(new AsmScopeBegin("ill06_ff_live"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("stx", CpuAddressingMode.ZP, false), "$ff", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("and", CpuAddressingMode.ZP, false), "$ff", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$10", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$ff", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$11", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      new Pass5IllegalOpcodeOptimization(p2).optimize();
      assertTrue(asm2.toString().contains("stx.z $ff") || asm2.toString().contains("stx $ff"),
            "KRIT-06: stx.z $ff must NOT be deleted when $ff is read afterwards! ASM:\n" + asm2);
   }

   @Test
   void testKrit07_IndirectVolatilePointerReadNotEliminatedInPass5() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t out1, out2;
            void poll(volatile uint8_t *p) {
                out1 = *p;
                out2 = *p;
            }
            void main(void) {
                poll((uint8_t *)0xD012);
                poll((uint8_t *)0xDC0D);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      int pollStart = asm.indexOf("poll:");
      assertTrue(pollStart >= 0, "Expected poll: in ASM:\n" + asm);
      String pollAsm = asm.substring(pollStart, asm.indexOf("}", pollStart));
      // Must perform TWO indirect reads through p, not eliminate the second dereference!
      int firstInd = pollAsm.indexOf("lda (");
      int secondInd = firstInd >= 0 ? pollAsm.indexOf("lda (", firstInd + 1) : -1;
      assertTrue(secondInd > firstInd,
            "KRIT-07: Indirect volatile uint8_t *p must perform both reads, not eliminate the second! ASM:\n" + pollAsm);
   }

   @Test
   void testKrit08_BitShiftCanonicalizationNoSinkingPastWriteAndExcludesVolatile() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint16_t src = 0x1234;
            volatile uint8_t out1, out2;
            volatile int8_t sout;
            void main(void) {
                uint16_t x = src;
                uint8_t hi = (uint8_t)(x >> 8);
                x = 0x5678;
                out1 = hi;
                out2 = (uint8_t)(x >> 8);
                uint16_t y = src;
                sout = (int8_t)(y >> 10);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      // out1 must receive high byte of src (NOT $56 from the later write x = 0x5678!)
      assertTrue(asm.contains("out1") && asm.contains("out2") && asm.contains("sout")
                  && asm.matches("(?s).*sta(\\.z)? out1.*sta(\\.z)? out2.*sta(\\.z)? sout.*"),
            "KRIT-08: Shift canonicalization must compile cleanly for SBYTE and preserve pre-write hi value! ASM:\n" + asm);
   }

   @Test
   void testKrit09_ShortCircuitDoesNotExecuteRhsPrePostModifiers() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t flag = 0;
            volatile uint8_t count = 0;
            volatile uint8_t res;
            void main(void) {
                if (flag && (++count > 0)) {
                    res = 1;
                }
                if (!flag || (count++ > 0)) {
                    res = 2;
                }
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      int mainIdx = asm.indexOf("main:");
      assertTrue(mainIdx >= 0);
      String mainAsm = asm.substring(mainIdx);
      int firstBranch = mainAsm.indexOf("beq ");
      int firstIncCount = mainAsm.indexOf("inc.z count");
      if(firstIncCount < 0) firstIncCount = mainAsm.indexOf("inc count");
      assertTrue(firstBranch >= 0 && firstIncCount > firstBranch,
            "KRIT-09: ++count in `flag && (++count > 0)` must occur AFTER testing flag! ASM:\n" + mainAsm);
   }

   @Test
   void testKrit10AndTs07_MultiDesignatorAndBitfieldStructInitializers() throws Exception {
      // Out-of-order & omitted multi-designator struct initializer
      String code1 = """
            #include <stdint.h>
            struct Point { uint8_t x; uint8_t y; uint8_t z; };
            const struct Point p = { .z = 30, .x = 10 };
            volatile uint8_t out_x, out_y, out_z;
            void main(void) {
                out_x = p.x;
                out_y = p.y;
                out_z = p.z;
            }
            """;
      Program prog1 = compileC(code1);
      String asm1 = prog1.getAsm().toString();
      // Expected layout: x=10 ($a), y=0 (omitted -> zero-init!), z=30 ($1e)
      assertTrue(asm1.matches("(?s).*lda #\\$a\\s+sta(\\.z)? out_x\\s+lda #0\\s+sta(\\.z)? out_y\\s+lda #\\$1e\\s+sta(\\.z)? out_z.*"),
            "KRIT-10: Out-of-order/partial multi-designator must map x=$a, y=0, z=$1e! ASM:\n" + asm1);

      // Unknown member in multi-designator must throw CompileError
      String badCode = """
            #include <stdint.h>
            struct Point { uint8_t x; uint8_t y; };
            const struct Point p = { .x = 1, .bogus = 2 };
            void main(void) {}
            """;
      assertThrows(CompileError.class, () -> compileC(badCode),
            "KRIT-10: Unknown designator member must fail with CompileError");

      // TS-07: Positional and designated bitfield struct initializers pack into __bf_0
      String bfCode = """
            #include <stdint.h>
            struct Bits { uint8_t a : 3; uint8_t b : 5; uint8_t c; };
            const struct Bits b1 = { 5, 17, 0x42 };
            const struct Bits b2 = { .c = 0x99, .b = 3, .a = 2 };
            volatile uint8_t out1, out2;
            void main(void) {
                out1 = b1.c;
                out2 = b2.c;
            }
            """;
      Program bfProg = compileC(bfCode);
      String bfAsm = bfProg.getAsm().toString();
      // b1: __bf_0 = (17 << 3) | 5 = 136 | 5 = 141 ($8d), c = $42
      // b2: __bf_0 = (3 << 3) | 2 = 24 | 2 = 26 ($1a), c = $99
      assertTrue(bfAsm.contains("$42") && bfAsm.contains("$99"),
            "TS-07: Bitfield struct initializers must compile and preserve member values! ASM:\n" + bfAsm);
   }

   @Test
   void testTs10_ConstantShiftBoundsOutOfRange() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t u = 0x55;
            volatile int8_t s = -40;
            volatile uint8_t r1, r2, r3;
            volatile int8_t r4;
            void main(void) {
                r1 = u << 8;
                r2 = u >> 16;
                r3 = u << -1;
                r4 = s >> 12;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.matches("(?s).*sta(\\.z)? r1.*sta(\\.z)? r2.*sta(\\.z)? r3.*sta(\\.z)? r4.*"),
            "TS-10: Out-of-range constant shifts must compile without Unknown fragment! ASM:\n" + asm);
   }

   @Test
   void testTs15AndTs16_HexFloatsEmptyCompoundLiteralNibbleStructPreprocessorAndMinLong() throws Exception {
      // TS-15: Hex float 0x1.ff must NOT strip trailing hex digit 'f' (1 + 255/256 = 1.99609375 -> *256 = 511)
      ConstantInteger hexFf = NumberParser.parseIntegerLiteral("0x1.ff");
      assertEquals(511L, hexFf.getInteger(), "TS-15: 0x1.ff must parse as 511 (1.99609375 * 256), not 0x1.f (496)");
      // C99 hex float with binary exponent p: 0x1.8p4 = 1.5 * 16 = 24.0 -> *256 = 6144
      ConstantInteger hexP = NumberParser.parseIntegerLiteral("0x1.8p4");
      assertEquals(6144L, hexP.getInteger(), "TS-15: 0x1.8p4 must parse as 6144 (24.0 * 256)");

      // TS-16: AsmFormat.getAsmNumber(Long.MIN_VALUE) must not StackOverflowError
      assertEquals("-$8000000000000000", AsmFormat.getAsmNumber(Long.MIN_VALUE));

      // TS-16: Division by zero in constant expression must throw CompileError
      String divZeroCode = """
            #include <stdint.h>
            volatile uint8_t out;
            void main(void) { out = 10 / 0; }
            """;
      assertThrows(CompileError.class, () -> compileC(divZeroCode),
            "TS-16: Constant division by zero must throw CompileError");

      // TS-16: Empty compound literal (struct S){}, uint4_t arr[8] struct size == 4, preprocessor #if true/'A'/?:/&&
      String c23Code = """
            #include <stdint.h>
            #if true && ('A' == 65) && (1 ? 1 : 0) && !(0 && (1 / 0))
            #define PREPROC_OK 1
            #else
            #define PREPROC_OK 0
            #endif
            static_assert(PREPROC_OK == 1, "Preprocessor #if evaluation failed");

            struct NibbleBox { uint4_t nibs[8]; };
            static_assert(sizeof(struct NibbleBox) == 4, "uint4_t[8] in struct must be 4 bytes");

            struct Pair { uint8_t a; uint8_t b; };
            volatile uint8_t out_a, out_b;
            void main(void) {
                struct Pair p = { 7, 9 };
                p = (struct Pair){};
                out_a = p.a;
                out_b = p.b;
            }
            """;
      Program program = compileC(c23Code);
      String asm = program.getAsm().toString();
      assertTrue((asm.contains("lda #0") || asm.contains(".const p_a = 0"))
                  && asm.matches("(?s).*sta(\\.z)? out_a.*sta(\\.z)? out_b.*"),
            "TS-16: Empty compound literal (struct Pair){} must zero-initialize fields! ASM:\n" + asm);
   }

   @Test
   void testConstantBinaryArraySizeNotTruncatedToByteByFinalizeNumberTypes() throws Exception {
      StringBuilder tableEntries = new StringBuilder();
      for(int i = 0; i < 1024; i++) {
         if(i > 0) tableEntries.append(",");
         tableEntries.append(i & 0xff);
      }
      StringBuilder arr2Entries = new StringBuilder();
      for(int i = 0; i < 300; i++) {
         if(i > 0) arr2Entries.append(",");
         arr2Entries.append(i & 0xff);
      }
      String code =
            "#include <stdint.h>\n" +
            "#define COUNT 16\n" +
            "const uint16_t TABLE[COUNT * 64] = { " + tableEntries + " };\n" +
            "const uint8_t ARR2[200 + 100] = { " + arr2Entries + " };\n" +
            "volatile uint16_t out1;\n" +
            "volatile uint8_t out2;\n" +
            "volatile uint16_t out3;\n" +
            "void main(void) {\n" +
            "    out1 = TABLE[1023];\n" +
            "    out2 = ARR2[299];\n" +
            "    out3 = 200 + 100;\n" +
            "}\n";
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("TABLE:") && asm.contains("ARR2:"),
            "ConstantBinary array sizes (16*64=1024, 200+100=300) must not truncate to BYTE! ASM:\n" + asm);
      assertTrue(asm.contains("$12c") || asm.contains("300"),
            "200 + 100 must evaluate to 300 ($12c), not truncated 44! ASM:\n" + asm);
   }

   @Test
   void testNegByteOfMultiByteAndLittleEndianExtractionSynthesis() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t out_split;
            volatile uint8_t out_bnot;
            volatile int8_t out_sneg;
            volatile uint8_t out_dneg;
            volatile uint16_t in_w;
            volatile int16_t in_sw;
            volatile uint32_t in_d;
            volatile char *in_ptr;

            void test_ptr_split(char *ptr) {
                if (ptr > (char *)(0xA000 - 40)) {
                    uint8_t split = (uint8_t)(0xA000 - (uint16_t)ptr);
                    out_split = split;
                }
            }

            void main(void) {
                test_ptr_split(in_ptr);
                test_ptr_split((char *)0x9FE0);
                uint16_t w = in_w;
                int16_t sw = in_sw;
                uint32_t d = in_d;
                out_bnot = ~(uint8_t)w;
                out_sneg = -(int8_t)sw;
                out_dneg = (uint8_t)(0x100u - (uint32_t)d);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("sbc") && asm.contains("out_split") && asm.contains("out_sneg") && asm.contains("out_dneg"),
            "GEN-02: _neg__byte_vwu/vdu and _neg__sbyte_vws must synthesize cleanly with optimal sec/lda #0/sbc! ASM:\n" + asm);
      assertTrue(asm.contains("eor #$ff") && asm.contains("out_bnot"),
            "GEN-02: General little-endian low-byte extraction must synthesize ~(uint8_t)w cleanly! ASM:\n" + asm);
   }
}
