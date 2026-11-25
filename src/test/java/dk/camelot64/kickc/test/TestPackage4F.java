package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.LiveRangeEquivalenceClass;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
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
 * Regression tests for Package 4F:
 * SSA-05, SSA-06, SSA-08, SSA-09, SSA-10.
 */
public class TestPackage4F {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4f_", ".c");
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
   void testSsa05_RootScopeIsrAndMainVarsDoNotShareZp() throws Exception {
      String code = """
            #include <stdint.h>
            static __ma uint8_t irq_phase = 0;
            static __ma uint8_t main_scratch = 0;
            volatile __address(0xD020) uint8_t BORDER;

            __interrupt(rom_min_c64) void isr(void) {
                irq_phase++;
                BORDER = irq_phase;
            }

            void main(void) {
                *((void(**)(void))0x0314) = &isr;
                for (;;) {
                    main_scratch = 42;
                    BORDER = main_scratch;
                }
            }
            """;
      Program program = compileC(code);
      Variable irqVar = program.getScope().getLocalVariable("irq_phase");
      Variable mainVar = program.getScope().getLocalVariable("main_scratch");
      assertNotNull(irqVar, "irq_phase should exist");
      assertNotNull(mainVar, "main_scratch should exist");
      Registers.Register irqReg = irqVar.getAllocation();
      Registers.Register mainReg = mainVar.getAllocation();
      assertNotNull(irqReg);
      assertNotNull(mainReg);
      assertNotEquals(irqReg, mainReg,
            "ISR file-scope variable irq_phase and main variable main_scratch must NOT share register " + irqReg);
   }

   @Test
   void testSsa06_HardwareClobberInterruptNoNpe() throws Exception {
      String code = """
            #include <stdint.h>
            volatile __address(0xD020) uint8_t BORDER;

            __interrupt(hardware_clobber) void irq_handler(void) {
                BORDER++;
            }

            void main(void) {
                *((void(**)(void))0x0314) = &irq_handler;
            }
            """;
      Program program = assertDoesNotThrow(() -> compileC(code));
      assertNotNull(program.getAsm());
   }

   @Test
   void testSsa08_ParallelPhiTransitionNoLostCopy() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t g_a, g_b, g_n, out;
            uint8_t swap_loop(uint8_t a0, __mem uint8_t b0, uint8_t n) {
                __zp uint8_t a = a0;
                __zp uint8_t b = b0;
                while (n-- > 0) {
                    __mem uint8_t next_b = a + 1;
                    uint8_t next_a = b;
                    a = next_a;
                    b = next_b;
                }
                return a ^ b;
            }
            void main(void) {
                out = swap_loop(g_a, g_b, g_n);
            }
            """;
      Program program = compileC(code);
      var interference = program.getLiveRangeInterference();
      var ecSet = program.getLiveRangeEquivalenceClassSet();
      int checkedPairs = 0;
      for (var block : program.getGraph().getAllBlocks()) {
         if (block.hasPhiBlock() && block.getPhiBlock().getPhiVariables().size() > 1) {
            var phiVars = block.getPhiBlock().getPhiVariables();
            for (int i = 0; i < phiVars.size(); i++) {
               var lhs = phiVars.get(i).getVariable();
               var ecLhs = ecSet.getEquivalenceClass(lhs);
               if (ecLhs == null) continue;
               for (int j = 0; j < phiVars.size(); j++) {
                  if (i == j) continue;
                  for (var phiRVal : phiVars.get(j).getValues()) {
                     if (phiRVal.getrValue() instanceof dk.camelot64.kickc.model.values.VariableRef rhs) {
                        var ecRhs = ecSet.getEquivalenceClass(rhs);
                        if (ecRhs != null && !ecLhs.equals(ecRhs)) {
                           checkedPairs++;
                           assertTrue(interference.interferes(ecLhs, ecRhs),
                                 "Phi LHS " + lhs + " must interfere with parallel phi RHS " + rhs);
                        }
                     }
                  }
               }
            }
         }
      }
      assertTrue(checkedPairs > 0, "Expected at least one multi-variable phi pair to verify");
   }

   @Test
   void testSsa09_PhiMemCoalesceDoesNotMergeVolatileAddress() throws Exception {
      String code = """
            #include <stdint.h>
            volatile __address(0xD012) uint8_t RASTER;
            volatile uint8_t g_mode;
            volatile uint8_t out;

            uint8_t read_or_zero(uint8_t mode) {
                uint8_t v = 0;
                if (mode) goto do_read;
            merge:
                return v;
            do_read:
                v = RASTER;
                goto merge;
            }

            void main(void) {
                out = read_or_zero(g_mode);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      int procIdx = asm.indexOf("read_or_zero:");
      assertTrue(procIdx >= 0, "Expected read_or_zero in ASM:\n" + asm);
      String procAsm = asm.substring(procIdx);
      assertFalse(procAsm.contains("sta RASTER") || procAsm.contains("sta $d012"),
            "v = 0 must NEVER write to volatile __address(0xD012) RASTER! ASM:\n" + procAsm);
   }

   @Test
   void testSsa10_MainMemPointerReservesFeFf() throws Exception {
      String code = """
            #include <stdint.h>
            #pragma zp_reserve(0x02..0xFD)
            uint8_t * __mem mem_ptr = (uint8_t *)0x0400;
            volatile uint8_t g_in;
            volatile uint8_t g_out;

            void main(void) {
                uint8_t a = mem_ptr[g_in];
                uint8_t b = mem_ptr[g_in + 1];
                g_out = a + b;
            }
            """;
      Program program = compileC(code);
      for (LiveRangeEquivalenceClass ec : program.getLiveRangeEquivalenceClassSet().getEquivalenceClasses()) {
         Registers.Register reg = ec.getRegister();
         if (reg instanceof Registers.RegisterZpMem zpReg) {
            int zp = zpReg.getZp();
            int bytes = zpReg.getBytes();
            for (int i = 0; i < bytes; i++) {
               assertTrue(zp + i != 0xFE && zp + i != 0xFF,
                     "When RegisterMainMem pointers exist, $FE/$FF must not be allocated to " + ec + " (got " + zpReg + ")");
            }
         }
      }
   }
}
