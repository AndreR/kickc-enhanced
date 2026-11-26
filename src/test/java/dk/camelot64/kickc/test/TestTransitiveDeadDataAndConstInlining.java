package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementCall;
import dk.camelot64.kickc.model.statements.StatementReturn;
import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.PassNTransitiveDeadDataElimination;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests transitive dead code/data elimination (Issue #59) and
 * cross-scope scalar constant inlining / storage elimination (Issue #63).
 */
public class TestTransitiveDeadDataAndConstInlining {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_dead_const_", ".c");
      Files.writeString(tempFile, source);
      try {
         Compiler compiler = new Compiler();
         compiler.setUpliftCombinations(20);
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
   void testDeadConstTableOnlyUsedByDeadFunctionIsEliminated() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "const uint8_t DEAD_TABLE[4] = { 0x11, 0x22, 0x33, 0x44 };\n"
            + "volatile uint8_t* const OUT = (uint8_t*)0x0400;\n"
            + "void dead_helper(uint8_t idx) {\n"
            + "    *OUT = DEAD_TABLE[idx];\n"
            + "}\n"
            + "void dead_entry(uint8_t x) {\n"
            + "    dead_helper(x);\n"
            + "}\n"
            + "void main(void) {\n"
            + "    *OUT = 0x55;\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();
      assertNull(program.getScope().getLocalSymbol("DEAD_TABLE"), "DEAD_TABLE should be eliminated from ProgramScope");
      assertNull(program.getScope().getLocalProcedure("dead_entry"), "dead_entry should be eliminated");
      assertNull(program.getScope().getLocalProcedure("dead_helper"), "dead_helper should be eliminated");
      assertFalse(asm.contains("DEAD_TABLE"), "DEAD_TABLE should not appear in generated ASM:\n" + asm);
      assertFalse(asm.contains("dead_entry"), "dead_entry should not appear in generated ASM:\n" + asm);
      assertFalse(asm.contains("dead_helper"), "dead_helper should not appear in generated ASM:\n" + asm);
   }

   @Test
   void testTransitiveReachableChainPreservedAndDeadChainEliminated() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "const uint8_t LIVE_LEAF[3] = { 0xaa, 0xbb, 0xcc };\n"
            + "const uint8_t* const LIVE_PTRS[1] = { LIVE_LEAF };\n"
            + "const uint8_t DEAD_LEAF[3] = { 0xde, 0xad, 0xbe };\n"
            + "const uint8_t* const DEAD_PTRS[1] = { DEAD_LEAF };\n"
            + "volatile uint8_t* const OUT = (uint8_t*)0x0400;\n"
            + "void live_fn(uint8_t i) {\n"
            + "    const uint8_t* p = LIVE_PTRS[0];\n"
            + "    *OUT = p[i];\n"
            + "}\n"
            + "void dead_fn2(uint8_t i) {\n"
            + "    const uint8_t* p = DEAD_PTRS[0];\n"
            + "    *OUT = p[i];\n"
            + "}\n"
            + "void dead_fn1(uint8_t i) {\n"
            + "    if (i > 0) dead_fn2(i);\n"
            + "}\n"
            + "void main(void) {\n"
            + "    live_fn(1);\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();
      assertNotNull(program.getScope().getLocalSymbol("LIVE_LEAF"), "LIVE_LEAF must be retained");
      assertNotNull(program.getScope().getLocalSymbol("LIVE_PTRS"), "LIVE_PTRS must be retained");
      assertTrue(asm.contains("LIVE_LEAF"), "LIVE_LEAF must appear in ASM:\n" + asm);
      assertNull(program.getScope().getLocalSymbol("DEAD_LEAF"), "DEAD_LEAF must be eliminated");
      assertNull(program.getScope().getLocalSymbol("DEAD_PTRS"), "DEAD_PTRS must be eliminated");
      assertFalse(asm.contains("DEAD_LEAF"), "DEAD_LEAF must not appear in ASM:\n" + asm);
      assertFalse(asm.contains("DEAD_PTRS"), "DEAD_PTRS must not appear in ASM:\n" + asm);
      assertFalse(asm.contains("dead_fn1"), "dead_fn1 must not appear in ASM:\n" + asm);
      assertFalse(asm.contains("dead_fn2"), "dead_fn2 must not appear in ASM:\n" + asm);
   }

   @Test
   void testCrossScopeGlobalScalarConstantInlinedAsImmediateAndNoStorageEmitted() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "const uint8_t MAX_VAL = 42;\n"
            + "volatile uint8_t* const OUT = (uint8_t*)0x0400;\n"
            + "void f1(void) {\n"
            + "    OUT[0] = MAX_VAL;\n"
            + "}\n"
            + "void f2(void) {\n"
            + "    OUT[1] = MAX_VAL;\n"
            + "}\n"
            + "void main(void) {\n"
            + "    f1();\n"
            + "    f2();\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();
      assertNull(program.getScope().getLocalSymbol("MAX_VAL"), "Inlined scalar constant MAX_VAL should be removed from ProgramScope");
      assertFalse(asm.contains(".const MAX_VAL"), "MAX_VAL should not emit .const directive:\n" + asm);
      assertFalse(asm.contains(".label MAX_VAL"), "MAX_VAL should not emit .label directive:\n" + asm);
      assertFalse(asm.contains("MAX_VAL"), "MAX_VAL symbol should be fully inlined in ASM:\n" + asm);
      assertTrue(asm.contains("#$2a"), "Expected immediate #$2a (42) in ASM:\n" + asm);
   }

   @Test
   void testScalarConstantWithAddressOfOrKickAsmUsesIsPreserved() throws Exception {
      String src = ""
            + "#include <stdint.h>\n"
            + "const uint8_t K_CONST = 7;\n"
            + "const uint8_t ADDR_CONST = 9;\n"
            + "volatile uint8_t* const OUT = (uint8_t*)0x0400;\n"
            + "void use_addr(void) {\n"
            + "    const uint8_t* p = &ADDR_CONST;\n"
            + "    OUT[0] = *p;\n"
            + "}\n"
            + "void use_kasm(void) {\n"
            + "    kickasm(uses K_CONST) {{\n"
            + "        lda #K_CONST\n"
            + "        sta $0401\n"
            + "    }}\n"
            + "}\n"
            + "void main(void) {\n"
            + "    use_addr();\n"
            + "    use_kasm();\n"
            + "}\n";

      Program program = compileC(src);
      String asm = program.getAsm().toString();
      assertTrue(asm.contains("K_CONST"), "Constant referenced by kickasm(uses ...) must remain in ASM:\n" + asm);
      assertTrue(asm.contains("ADDR_CONST"), "Constant whose address is taken must remain in ASM:\n" + asm);
   }

   @Test
   void testTransitiveDeadDataPassDirectlyOnCyclicDeadProceduresAndChainedTables() {
      Program program = new Program();
      ProgramScope scope = program.getScope();

      // Create tables: LIVE_B, LIVE_A -> LIVE_B, DEAD_B, DEAD_A -> DEAD_B, EXPORTED_CONST
      ConstantInteger one = new ConstantInteger(1L, SymbolType.BYTE);
      Variable liveB = new Variable("LIVE_B", Variable.Kind.CONSTANT, new SymbolTypePointer(SymbolType.BYTE), scope, Variable.MemoryArea.MAIN_MEMORY, "Data",
            new ConstantArrayList(List.of(new ConstantInteger(1L, SymbolType.BYTE)), SymbolType.BYTE, one));
      scope.add(liveB);
      Variable liveA = new Variable("LIVE_A", Variable.Kind.CONSTANT, new SymbolTypePointer(new SymbolTypePointer(SymbolType.BYTE)), scope, Variable.MemoryArea.MAIN_MEMORY, "Data",
            new ConstantArrayList(List.of(new ConstantSymbolPointer(liveB.getRef())), new SymbolTypePointer(SymbolType.BYTE), one));
      scope.add(liveA);

      Variable deadB = new Variable("DEAD_B", Variable.Kind.CONSTANT, new SymbolTypePointer(SymbolType.BYTE), scope, Variable.MemoryArea.MAIN_MEMORY, "Data",
            new ConstantArrayList(List.of(new ConstantInteger(2L, SymbolType.BYTE)), SymbolType.BYTE, one));
      scope.add(deadB);
      Variable deadA = new Variable("DEAD_A", Variable.Kind.CONSTANT, new SymbolTypePointer(new SymbolTypePointer(SymbolType.BYTE)), scope, Variable.MemoryArea.MAIN_MEMORY, "Data",
            new ConstantArrayList(List.of(new ConstantSymbolPointer(deadB.getRef())), new SymbolTypePointer(SymbolType.BYTE), one));
      scope.add(deadA);

      Variable exportedConst = new Variable("EXPORTED_CONST", Variable.Kind.CONSTANT, SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, "Data",
            new ConstantInteger(3L, SymbolType.BYTE));
      exportedConst.setExport(true);
      scope.add(exportedConst);

      Bank defaultBank = new Bank("Common", 0L);
      SymbolTypeProcedure procType = new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList());

      Procedure mainProc = new Procedure("main", procType, scope, "Code", "Data", Procedure.CallingConvention.PHI_CALL, defaultBank);
      scope.add(mainProc);
      program.setStartProcedure(mainProc.getRef());
      Procedure liveProc = new Procedure("live_fn", procType, scope, "Code", "Data", Procedure.CallingConvention.PHI_CALL, defaultBank);
      scope.add(liveProc);
      Procedure deadProc1 = new Procedure("dead_a", procType, scope, "Code", "Data", Procedure.CallingConvention.PHI_CALL, defaultBank);
      scope.add(deadProc1);
      Procedure deadProc2 = new Procedure("dead_b", procType, scope, "Code", "Data", Procedure.CallingConvention.PHI_CALL, defaultBank);
      scope.add(deadProc2);

      // Build CFGs: main -> live_fn -> LIVE_A; dead_a <-> dead_b -> DEAD_A
      ControlFlowBlock mainBlock = new ControlFlowBlock(mainProc.getRef().getLabelRef(), mainProc.getRef());
      mainBlock.addStatement(new StatementCall(null, liveProc.getRef().getFullName(), Collections.emptyList(), StatementSource.NONE, Comment.NO_COMMENTS));
      ((StatementCall) mainBlock.getStatements().get(0)).setProcedure(liveProc.getRef());
      mainBlock.addStatement(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
      program.createProcedureCompilation(mainProc.getRef()).setGraph(new ControlFlowGraph(new ArrayList<>(List.of(mainBlock))));

      ControlFlowBlock liveBlock = new ControlFlowBlock(liveProc.getRef().getLabelRef(), liveProc.getRef());
      liveBlock.addStatement(new StatementAssignment(null, null, null, new ConstantSymbolPointer(liveA.getRef()), true, StatementSource.NONE, Comment.NO_COMMENTS));
      liveBlock.addStatement(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
      program.createProcedureCompilation(liveProc.getRef()).setGraph(new ControlFlowGraph(new ArrayList<>(List.of(liveBlock))));

      ControlFlowBlock deadBlock1 = new ControlFlowBlock(deadProc1.getRef().getLabelRef(), deadProc1.getRef());
      StatementCall callDead2 = new StatementCall(null, deadProc2.getRef().getFullName(), Collections.emptyList(), StatementSource.NONE, Comment.NO_COMMENTS);
      callDead2.setProcedure(deadProc2.getRef());
      deadBlock1.addStatement(callDead2);
      deadBlock1.addStatement(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
      program.createProcedureCompilation(deadProc1.getRef()).setGraph(new ControlFlowGraph(new ArrayList<>(List.of(deadBlock1))));

      ControlFlowBlock deadBlock2 = new ControlFlowBlock(deadProc2.getRef().getLabelRef(), deadProc2.getRef());
      StatementCall callDead1 = new StatementCall(null, deadProc1.getRef().getFullName(), Collections.emptyList(), StatementSource.NONE, Comment.NO_COMMENTS);
      callDead1.setProcedure(deadProc1.getRef());
      deadBlock2.addStatement(callDead1);
      deadBlock2.addStatement(new StatementAssignment(null, null, null, new ConstantSymbolPointer(deadA.getRef()), true, StatementSource.NONE, Comment.NO_COMMENTS));
      deadBlock2.addStatement(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
      program.createProcedureCompilation(deadProc2.getRef()).setGraph(new ControlFlowGraph(new ArrayList<>(List.of(deadBlock2))));

      PassNTransitiveDeadDataElimination pass = new PassNTransitiveDeadDataElimination(program);
      assertTrue(pass.step(), "PassNTransitiveDeadDataElimination should report modifications");

      assertNotNull(scope.getLocalProcedure("main"));
      assertNotNull(scope.getLocalProcedure("live_fn"));
      assertNotNull(scope.getLocalSymbol("LIVE_A"));
      assertNotNull(scope.getLocalSymbol("LIVE_B"));
      assertNotNull(scope.getLocalSymbol("EXPORTED_CONST"));

      assertNull(scope.getLocalProcedure("dead_a"));
      assertNull(scope.getLocalProcedure("dead_b"));
      assertNull(scope.getLocalSymbol("DEAD_A"));
      assertNull(scope.getLocalSymbol("DEAD_B"));
      assertFalse(pass.step(), "Second pass should find nothing more to eliminate");
   }
}
