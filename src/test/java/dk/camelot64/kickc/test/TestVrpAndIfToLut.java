package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.PointerDereferenceIndexed;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass2IfToLutOptimization;
import dk.camelot64.kickc.passes.Pass2RangeNarrowing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and end-to-end compiler tests for:
 * - Value Range Propagation (VRP) & 16-to-8 bit expression narrowing ({@link Pass2RangeNarrowing})
 * - Dense contiguous if-else-if chain to constant LUT conversion ({@link Pass2IfToLutOptimization})
 */
public class TestVrpAndIfToLut {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_vrp_lut_", ".c");
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
   void testVrpNarrows16BitAvgExpressionTo8Bit() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_A = (uint8_t *)0x0400;
            volatile uint8_t * const PORT_B = (uint8_t *)0x0401;
            volatile uint8_t * const PORT_OUT = (uint8_t *)0x0402;

            uint8_t avg(uint8_t a, uint8_t b) {
                uint16_t sum = (uint16_t)(a & 0x3f) + (uint16_t)(b & 0x3f);
                return (uint8_t)(sum >> 1);
            }

            void main(void) {
                *PORT_OUT = avg(*PORT_A, *PORT_B);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      String log = program.getLog().toString();

      assertTrue(log.contains("VRP narrowed 16-bit expression"),
            "Expected VRP narrowing log entry, got:\n" + log);
      assertTrue(asm.contains("lsr"), "Expected 8-bit lsr shift in ASM:\n" + asm);
      assertFalse(asm.contains("ror "), "Did not expect 16-bit ror shift in ASM:\n" + asm);
   }

   @Test
   void testVrpNarrows8BitAvgWithoutExplicitCast() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_A = (uint8_t *)0x0400;
            volatile uint8_t * const PORT_B = (uint8_t *)0x0401;
            volatile uint8_t * const PORT_OUT = (uint8_t *)0x0402;

            uint8_t avg(uint8_t a, uint8_t b) {
                return ((a & 0x3f) + (b & 0x3f)) >> 1;
            }

            void main(void) {
                *PORT_OUT = avg(*PORT_A, *PORT_B);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("and #$3f") || asm.contains("anc #$3f"),
            "Expected &$3f mask (and #$3f or anc #$3f) in ASM:\n" + asm);
      assertTrue(asm.contains("lsr"), "Expected 8-bit lsr in ASM:\n" + asm);
      assertFalse(asm.contains("ror "), "Did not expect 16-bit ror shift in ASM:\n" + asm);
   }

   @Test
   void testVrpPreservesOpt05SignedByteDifference() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_A = (uint8_t *)0x0400;
            volatile uint8_t * const PORT_B = (uint8_t *)0x0401;
            volatile int16_t * const PORT_OUT = (int16_t *)0x0410;

            int16_t diff7(uint8_t a, uint8_t b) {
                uint8_t x = a & 0x7f;
                uint8_t y = b & 0x7f;
                return (int16_t)x - (int16_t)y;
            }

            void main(void) {
                *PORT_OUT = diff7(*PORT_A, *PORT_B);
            }
            """;
      Program program = compileC(code);
      String log = program.getLog().toString();
      assertTrue(log.contains("OPT-05:"), "Expected OPT-05 rule to remain active:\n" + log);
   }

   @Test
   void testVrpDoesNotNarrowOutOf8BitRangeSum() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_A = (uint8_t *)0x0400;
            volatile uint8_t * const PORT_B = (uint8_t *)0x0401;
            volatile uint16_t * const PORT_OUT = (uint16_t *)0x0410;

            void main(void) {
                uint16_t sum = (uint16_t)(*PORT_A) + (uint16_t)(*PORT_B);
                *PORT_OUT = sum;
            }
            """;
      Program program = compileC(code);
      String log = program.getLog().toString();
      assertFalse(log.contains("VRP narrowed 16-bit expression"),
            "Full 8-bit + 8-bit can reach 510 and must NOT be narrowed to 8-bit");
   }

   @Test
   void testIfToLutConvertsContiguousChainToConstLutInAsm() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_DIR = (uint8_t *)0x0400;
            volatile int8_t * const PORT_OUT = (int8_t *)0x0401;

            int8_t dir_to_dx(uint8_t dir) {
                int8_t dx = 0;
                if (dir == 0) dx = 1;
                else if (dir == 1) dx = 2;
                else if (dir == 2) dx = 4;
                else if (dir == 3) dx = 8;
                return dx;
            }

            void main(void) {
                *PORT_OUT = dir_to_dx(*PORT_DIR);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("__lut_"), "Expected __lut_ constant array in ASM:\n" + asm);
      assertTrue(asm.contains(".byte 1, 2, 4, 8"), "Expected LUT byte table {1, 2, 4, 8} in ASM:\n" + asm);
   }

   @Test
   void testIfToLutWithNonZeroBaseAndElseBranchInAsm() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_DIR = (uint8_t *)0x0400;
            volatile int8_t * const PORT_OUT = (int8_t *)0x0401;

            int8_t map_code(uint8_t k) {
                int8_t v;
                if (k == 10) v = -2;
                else if (k == 11) v = -1;
                else if (k == 12) v = 1;
                else if (k == 13) v = 2;
                else v = 0;
                return v;
            }

            void main(void) {
                *PORT_OUT = map_code(*PORT_DIR);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("__lut_"), "Expected __lut_ constant array in ASM:\n" + asm);
      assertTrue(asm.contains(".byte -2, -1, 1, 2") || asm.contains(".byte $fe, $ff, 1, 2"),
            "Expected signed LUT entries {-2, -1, 1, 2} in ASM:\n" + asm);
   }

   @Test
   void testIfToLutOmitsBoundsCheckWhenVrpProvesRange() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const PORT_DIR = (uint8_t *)0x0400;
            volatile uint8_t * const PORT_OUT = (uint8_t *)0x0401;

            uint8_t lookup_masked(uint8_t raw) {
                uint8_t dir = raw & 3;
                uint8_t out = 0;
                if (dir == 0) out = 11;
                else if (dir == 1) out = 22;
                else if (dir == 2) out = 33;
                else if (dir == 3) out = 44;
                return out;
            }

            void main(void) {
                *PORT_OUT = lookup_masked(*PORT_DIR);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("__lut_"), "Expected __lut_ constant array in ASM:\n" + asm);
      assertTrue(asm.contains(".byte $b, $16, $21, $2c") || asm.contains(".byte 11, 22, 33, 44"),
            "Expected LUT {11, 22, 33, 44} in ASM:\n" + asm);
      assertFalse(asm.contains("cmp #4") || asm.contains("cmp #$4"),
            "Bounds check cmp #4 should be omitted when VRP proves dir in [0, 3]:\n" + asm);
   }

   @Test
   void testIfToLutDirectIrTransformation() {
      // Standalone SSA-IR test verifying Pass2IfToLutOptimization independently
      Program program = new Program();
      program.setTargetPlatform(new TargetPlatform("c64"));
      ProgramScope scope = program.getScope();
      Procedure proc = new Procedure(
            "fn",
            new SymbolTypeProcedure(SymbolType.BYTE, List.of(SymbolType.BYTE)),
            scope,
            Scope.SEGMENT_CODE_DEFAULT,
            Scope.SEGMENT_DATA_DEFAULT,
            Procedure.CallingConvention.PHI_CALL,
            Bank.COMMON);
      scope.add(proc);

      Variable dir = new Variable("dir", Variable.Kind.PHI_VERSION, SymbolType.BYTE, proc, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
      Variable c0v = new Variable("v0", Variable.Kind.INTERMEDIATE, SymbolType.BYTE, proc, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
      Variable c1v = new Variable("v1", Variable.Kind.INTERMEDIATE, SymbolType.BYTE, proc, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
      Variable c2v = new Variable("v2", Variable.Kind.INTERMEDIATE, SymbolType.BYTE, proc, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
      Variable c3v = new Variable("v3", Variable.Kind.INTERMEDIATE, SymbolType.BYTE, proc, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
      Variable res = new Variable("res", Variable.Kind.PHI_VERSION, SymbolType.BYTE, proc, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
      proc.add(dir);
      proc.add(c0v);
      proc.add(c1v);
      proc.add(c2v);
      proc.add(c3v);
      proc.add(res);

      Label lC0 = proc.addLabel("fn");
      Label lC1 = proc.addLabel("c1");
      Label lC2 = proc.addLabel("c2");
      Label lC3 = proc.addLabel("c3");
      Label lT0 = proc.addLabel("t0");
      Label lT1 = proc.addLabel("t1");
      Label lT2 = proc.addLabel("t2");
      Label lT3 = proc.addLabel("t3");
      Label lMerge = proc.addLabel("merge");

      ControlFlowBlock bC0 = new ControlFlowBlock(lC0.getRef(), proc.getRef());
      ControlFlowBlock bC1 = new ControlFlowBlock(lC1.getRef(), proc.getRef());
      ControlFlowBlock bC2 = new ControlFlowBlock(lC2.getRef(), proc.getRef());
      ControlFlowBlock bC3 = new ControlFlowBlock(lC3.getRef(), proc.getRef());
      ControlFlowBlock bT0 = new ControlFlowBlock(lT0.getRef(), proc.getRef());
      ControlFlowBlock bT1 = new ControlFlowBlock(lT1.getRef(), proc.getRef());
      ControlFlowBlock bT2 = new ControlFlowBlock(lT2.getRef(), proc.getRef());
      ControlFlowBlock bT3 = new ControlFlowBlock(lT3.getRef(), proc.getRef());
      ControlFlowBlock bMerge = new ControlFlowBlock(lMerge.getRef(), proc.getRef());

      bC0.addStatement(new StatementConditionalJump(dir.getRef(), Operators.EQ, new ConstantInteger(0L, SymbolType.BYTE), lT0.getRef(), null, Comment.NO_COMMENTS));
      bC0.setConditionalSuccessor(lT0.getRef());
      bC0.setDefaultSuccessor(lC1.getRef());

      bC1.addStatement(new StatementConditionalJump(dir.getRef(), Operators.EQ, new ConstantInteger(1L, SymbolType.BYTE), lT1.getRef(), null, Comment.NO_COMMENTS));
      bC1.setConditionalSuccessor(lT1.getRef());
      bC1.setDefaultSuccessor(lC2.getRef());

      bC2.addStatement(new StatementConditionalJump(dir.getRef(), Operators.EQ, new ConstantInteger(2L, SymbolType.BYTE), lT2.getRef(), null, Comment.NO_COMMENTS));
      bC2.setConditionalSuccessor(lT2.getRef());
      bC2.setDefaultSuccessor(lC3.getRef());

      bC3.addStatement(new StatementConditionalJump(dir.getRef(), Operators.EQ, new ConstantInteger(3L, SymbolType.BYTE), lT3.getRef(), null, Comment.NO_COMMENTS));
      bC3.setConditionalSuccessor(lT3.getRef());
      bC3.setDefaultSuccessor(lMerge.getRef());

      bT0.addStatement(new StatementAssignment(c0v.getVariableRef(), null, null, new ConstantInteger(1L, SymbolType.BYTE), true, null, Comment.NO_COMMENTS));
      bT0.setDefaultSuccessor(lMerge.getRef());
      bT1.addStatement(new StatementAssignment(c1v.getVariableRef(), null, null, new ConstantInteger(2L, SymbolType.BYTE), true, null, Comment.NO_COMMENTS));
      bT1.setDefaultSuccessor(lMerge.getRef());
      bT2.addStatement(new StatementAssignment(c2v.getVariableRef(), null, null, new ConstantInteger(4L, SymbolType.BYTE), true, null, Comment.NO_COMMENTS));
      bT2.setDefaultSuccessor(lMerge.getRef());
      bT3.addStatement(new StatementAssignment(c3v.getVariableRef(), null, null, new ConstantInteger(8L, SymbolType.BYTE), true, null, Comment.NO_COMMENTS));
      bT3.setDefaultSuccessor(lMerge.getRef());

      StatementPhiBlock phi = new StatementPhiBlock(Comment.NO_COMMENTS);
      StatementPhiBlock.PhiVariable pv = phi.addPhiVariable(res.getVariableRef());
      pv.setrValue(lT0.getRef(), c0v.getRef());
      pv.setrValue(lT1.getRef(), c1v.getRef());
      pv.setrValue(lT2.getRef(), c2v.getRef());
      pv.setrValue(lT3.getRef(), c3v.getRef());
      pv.setrValue(lC3.getRef(), new ConstantInteger(0L, SymbolType.BYTE));
      bMerge.addStatement(phi);
      bMerge.setDefaultSuccessor(new LabelRef(proc.getFullName() + "::@return"));

      List<Graph.Block> blocks = new ArrayList<>(List.of(bC0, bC1, bC2, bC3, bT0, bT1, bT2, bT3, bMerge));
      program.createProcedureCompilation(proc.getRef()).setGraph(new ControlFlowGraph(blocks));

      boolean changed = new Pass2IfToLutOptimization(program).step();
      assertTrue(changed, "Expected Pass2IfToLutOptimization to transform dense 4-case chain");
      assertNotNull(proc.getLocalSymbol("__lut_0"), "Expected __lut_0 constant in procedure scope");
      assertNull(program.getGraph().getBlock(lC1.getRef()), "Bypassed condition block c1 should be removed");
      assertNull(program.getGraph().getBlock(lT0.getRef()), "Bypassed branch block t0 should be removed");
      assertTrue(bC0.getStatements().get(bC0.getStatements().size() - 1) instanceof StatementConditionalJump);
      StatementConditionalJump rangeCheck = (StatementConditionalJump) bC0.getStatements().get(bC0.getStatements().size() - 1);
      assertEquals(Operators.LT, rangeCheck.getOperator());
      assertEquals(4L, ((ConstantInteger) rangeCheck.getrValue2()).getInteger());
      ControlFlowBlock lutBlock = (ControlFlowBlock) program.getGraph().getBlock(rangeCheck.getDestination());
      assertNotNull(lutBlock);
      assertTrue(((StatementAssignment) lutBlock.getStatements().get(0)).getrValue2() instanceof PointerDereferenceIndexed);
   }
}
