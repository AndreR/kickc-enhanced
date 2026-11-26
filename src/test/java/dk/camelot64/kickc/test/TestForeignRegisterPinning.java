package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.statements.StatementCall;
import dk.camelot64.kickc.model.statements.StatementReturn;
import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.passes.Pass1CallPhiParameters;
import dk.camelot64.kickc.passes.Pass1ModifiedVarsAnalysis;
import dk.camelot64.kickc.passes.Pass4AssertNoCpuClobber;
import dk.camelot64.kickc.passes.Pass4CodeGeneration;
import dk.camelot64.kickc.passes.Pass4DirectTrialEvaluator;
import dk.camelot64.kickc.passes.PassNStatementIndices;
import dk.camelot64.kickc.passes.calcs.PassNCalcLiveRangesEffectiveSimple;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for fixed-address foreign procedure calls (= $addr), explicit CPU register
 * parameter/return pinning (__reg(A/X/Y)), carry return (__carry), and conservative
 * Pass-4 CPU clobber handling.
 */
public class TestForeignRegisterPinning {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_foreign_pin_", ".c");
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
   void testFixedAddressWithRegParamsEmitsLoadsAndJsrNoBody() throws Exception {
      String code = """
            #include <stdint.h>
            void kernal_setlfs(uint8_t lfn __reg(A), uint8_t dev __reg(X), uint8_t sec __reg(Y)) = $FFBA;

            void main(void) {
                kernal_setlfs(1, 8, 15);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString().toLowerCase(Locale.ROOT);
      assertTrue(asm.contains("jsr $ffba"), "Expected JSR $ffba in ASM:\n" + asm);
      assertTrue(asm.contains("lda #1"), "Expected LDA #1 for __reg(A) in ASM:\n" + asm);
      assertTrue(asm.contains("ldx #8"), "Expected LDX #8 for __reg(X) in ASM:\n" + asm);
      assertTrue(asm.contains("ldy #$f"), "Expected LDY #$f for __reg(Y) in ASM:\n" + asm);
      assertFalse(asm.contains("kernal_setlfs:"), "Fixed-address foreign proc must not emit a label/body:\n" + asm);
   }

   @Test
   void testFixedAddressWithRegReturnEmitsJsrAndStoresReturn() throws Exception {
      String code = """
            #include <stdint.h>
            uint8_t kernal_basin(void) __reg(A) = $FFCF;
            uint8_t ext_status(void) __reg(X) = $FFE1;
            volatile uint8_t * const OUT = (uint8_t *)0x0400;

            void main(void) {
                OUT[0] = kernal_basin();
                OUT[1] = ext_status();
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString().toLowerCase(Locale.ROOT);
      assertTrue(asm.contains("jsr $ffcf"), "Expected JSR $ffcf in ASM:\n" + asm);
      assertTrue(asm.contains("jsr $ffe1"), "Expected JSR $ffe1 in ASM:\n" + asm);
      assertFalse(asm.contains("kernal_basin:"), "Fixed-address foreign proc must not emit a body:\n" + asm);
      assertFalse(asm.contains("ext_status:"), "Fixed-address foreign proc must not emit a body:\n" + asm);
   }

   @Test
   void testFixedAddressWithCarryReturnExtractsCarry() throws Exception {
      String code = """
            #include <stdint.h>
            #include <stdbool.h>
            bool kernal_readst(void) __carry = $FFB7;
            volatile uint8_t * const OUT = (uint8_t *)0x0402;

            void main(void) {
                bool err = kernal_readst();
                *OUT = err ? 1 : 0;
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString().toLowerCase(Locale.ROOT);
      assertTrue(asm.contains("jsr $ffb7"), "Expected JSR $ffb7 in ASM:\n" + asm);
      assertTrue(asm.contains("lda #0") && asm.contains("rol"), "Expected carry extraction (lda #0; rol) in ASM:\n" + asm);
      assertFalse(asm.contains("kernal_readst:"), "Fixed-address foreign proc must not emit a body:\n" + asm);
   }

   @Test
   void testLiveVariableAcrossFixedAddressCallDoesNotClobberCpuRegs() throws Exception {
      String code = """
            #include <stdint.h>
            void kernal_chrout(uint8_t ch __reg(A)) = $FFD2;
            volatile uint8_t * const IN = (uint8_t *)0x0410;
            volatile uint8_t * const OUT = (uint8_t *)0x0420;

            void main(void) {
                uint8_t keep = *IN;
                kernal_chrout(65);
                *OUT = keep + 1;
            }
            """;
      Program program = compileC(code);
      Pass4AssertNoCpuClobber clobberCheck = new Pass4AssertNoCpuClobber(program);
      assertFalse(clobberCheck.hasClobberProblem(false), "Expected no CPU clobber problem across fixed-address call");
      String asm = program.getAsm().toString().toLowerCase(Locale.ROOT);
      assertTrue(asm.contains("jsr $ffd2"), "Expected JSR $ffd2 in ASM:\n" + asm);
   }

   @Test
   void testIrDirectCallEvalClobberAndSwapScheduling() throws Exception {
      Path linkScript = Files.createTempFile("kickc_link_", ".ld");
      Files.writeString(linkScript, "");
      try {
         Program program = new Program();
         program.setMainFileComments(new ArrayList<>());
         TargetPlatform platform = new TargetPlatform("test6502");
         platform.setCpu(TargetCpu.MOS6502X);
         platform.setLinkScriptFile(linkScript.toFile());
         platform.setOutFileExtension("prg");
         program.setTargetPlatform(platform);
         program.getOutputFileManager().setBinaryExtension("prg");
         program.getOutputFileManager().setCurrentDir(Paths.get("."));
         program.getOutputFileManager().setPrimaryCFile(Paths.get("test_foreign_pin.c"));

         // Fixed-address foreign procedure: void fn(uint8_t a __reg(A), uint8_t b __reg(X)) = $FFBA
         SymbolTypeProcedure fnType = new SymbolTypeProcedure(SymbolType.VOID, Arrays.asList(SymbolType.BYTE, SymbolType.BYTE));
         Procedure fnProc = new Procedure("foreign_fn", fnType, program.getScope(),
               Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT,
               Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
         fnProc.setFixedAddress(new ConstantInteger(0xFFBAL, SymbolType.WORD));
         Variable pA = new Variable("a", Variable.Kind.PHI_MASTER, SymbolType.BYTE, fnProc, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
         pA.setRegister(Registers.getRegisterA());
         Variable pX = new Variable("b", Variable.Kind.PHI_MASTER, SymbolType.BYTE, fnProc, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
         pX.setRegister(Registers.getRegisterX());
         fnProc.add(pA);
         fnProc.add(pX);
         fnProc.setParameters(Arrays.asList(pA, pX));
         program.getScope().add(fnProc);
         ProcedureCompilation fnComp = program.createProcedureCompilation(fnProc.getRef());
         Label fnLabel = fnProc.getLabel();
         ControlFlowBlock fnBlock = new ControlFlowBlock(fnLabel.getRef(), fnProc.getRef());
         fnBlock.getStatements().add(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
         fnComp.setGraph(new ControlFlowGraph(Collections.singletonList(fnBlock)));

         // Caller main() with live swapped args (argForA in X, argForX in A) and liveAcross in Y
         SymbolTypeProcedure mainType = new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList());
         Procedure mainProc = new Procedure("main", mainType, program.getScope(),
               Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT,
               Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
         mainProc.setParameterNames(new ArrayList<>());
         program.getScope().add(mainProc);
         program.setStartProcedure(mainProc.getRef());

         Variable argForA = new Variable("v1", Variable.Kind.PHI_VERSION, SymbolType.BYTE, mainProc, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
         argForA.setAllocation(Registers.getRegisterX());
         Variable argForX = new Variable("v2", Variable.Kind.PHI_VERSION, SymbolType.BYTE, mainProc, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
         argForX.setAllocation(Registers.getRegisterA());
         Variable liveAcross = new Variable("keep", Variable.Kind.PHI_VERSION, SymbolType.BYTE, mainProc, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
         liveAcross.setAllocation(Registers.getRegisterY());
         mainProc.add(argForA);
         mainProc.add(argForX);
         mainProc.add(liveAcross);

         ProcedureCompilation mainComp = program.createProcedureCompilation(mainProc.getRef());
         ControlFlowBlock mainBlock = new ControlFlowBlock(mainProc.getLabel().getRef(), mainProc.getRef());
         mainBlock.setComments(new ArrayList<>());
         StatementCall callStmt = new StatementCall(null, "foreign_fn", Arrays.asList(argForA.getRef(), argForX.getRef()), StatementSource.NONE, new ArrayList<>());
         callStmt.setProcedure(fnProc.getRef());
         mainBlock.getStatements().add(callStmt);
         mainBlock.getStatements().add(new StatementReturn(liveAcross.getRef(), StatementSource.NONE, new ArrayList<>()));
         mainComp.setGraph(new ControlFlowGraph(Collections.singletonList(mainBlock)));

         // Pass1CallPhiParameters must switch fixed-address proc away from PHI_CALL and preserve StatementCall args
         new Pass1ModifiedVarsAnalysis(program).execute();
         new Pass1CallPhiParameters(program).execute();
         assertEquals(Procedure.CallingConvention.INTRINSIC_CALL, fnProc.getCallingConvention());
         assertNotNull(callStmt.getParameters());
         assertEquals(2, callStmt.getParameters().size());

         new PassNStatementIndices(program).execute();
         program.getLiveRangeVariablesEffective();

         // Pass4DirectTrialEvaluator must report full A/X/Y clobber
         CpuClobber evalClobber = Pass4DirectTrialEvaluator.evaluateStatementClobber(program, mainBlock, callStmt, new LinkedHashSet<>());
         assertNotNull(evalClobber);
         assertTrue(evalClobber.isRegisterA() && evalClobber.isRegisterX() && evalClobber.isRegisterY(),
               "Expected A, X, Y clobber on fixed-address call, got: " + evalClobber);

         // Pass4CodeGeneration must skip foreign_fn body and resolve A<->X swap via $fe cleanly
         new Pass4CodeGeneration(program, false, false).generate();
         AsmProgram asm = program.getAsm();
         String asmText = asm.toString().toLowerCase(Locale.ROOT);
         assertFalse(asmText.contains("foreign_fn:"), "Foreign proc must not emit scope/label:\n" + asmText);
         assertTrue(asmText.contains("stx $fe") || asmText.contains("stx.z $fe"), "Expected stx $fe to break A<->X swap:\n" + asmText);
         assertTrue(asmText.contains("tax"), "Expected tax in A<->X swap:\n" + asmText);
         assertTrue(asmText.contains("lda $fe") || asmText.contains("lda.z $fe"), "Expected lda $fe in A<->X swap:\n" + asmText);
         assertTrue(asmText.contains("jsr $ffba"), "Expected jsr $ffba:\n" + asmText);

         // Pass4AssertNoCpuClobber must detect that liveAcross in Y is clobbered by foreign_fn call
         assertTrue(new Pass4AssertNoCpuClobber(program).hasClobberProblem(false),
               "Expected clobber detection when liveAcross stays in Y across fixed-address call");

         // Re-allocating liveAcross to ZP resolves the clobber problem
         liveAcross.setAllocation(new Registers.RegisterZpMem(2, 1));
         assertFalse(new Pass4AssertNoCpuClobber(program).hasClobberProblem(false),
               "Expected no clobber problem when liveAcross is in ZP");
      } finally {
         Files.deleteIfExists(linkScript);
      }
   }
}
