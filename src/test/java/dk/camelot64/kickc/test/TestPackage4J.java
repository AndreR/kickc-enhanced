package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass5IllegalOpcodeOptimization;
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
 * Regression tests for Package 4J:
 * CF-11, ILL-02, ILL-03.
 */
public class TestPackage4J {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_4j_", ".c");
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
   void testCf11_LabeledStatementInsideIfBranch() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t g_cond;
            volatile uint8_t g_res;
            uint8_t labeled_if(uint8_t cond) {
                uint8_t x = 0;
                if (cond)
                    hit: x = 42;
                return x;
            }
            void main(void) {
                g_res = labeled_if(g_cond);
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      int procIdx = asm.indexOf("labeled_if:");
      assertTrue(procIdx >= 0, "Expected labeled_if in ASM:\n" + asm);
      String procAsm = asm.substring(procIdx);
      assertTrue(procAsm.contains("beq ") || procAsm.contains("bne "),
            "if (cond) hit: x = 42; must keep x = 42 inside the conditional branch! ASM:\n" + procAsm);
      assertTrue(procAsm.contains("#0") && (procAsm.contains("#$2a") || procAsm.contains("#42")),
            "labeled_if must be able to return both 0 and 42! ASM:\n" + procAsm);
   }

   @Test
   void testIll02_RmwIllegalBlockedOnHardwareIoAndFoldedOnZpAlias() {
      Program program = new Program();
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();
      AsmProgram asm = new AsmProgram(TargetCpu.MOS6502X);
      program.setAsm(asm);

      // Part 1: Hardware I/O ($d020) must NOT fold dec; cmp into dcp
      AsmChunk ioChunk = asm.startChunk(ScopeRef.ROOT, null, "io_test");
      ioChunk.addLine(new AsmScopeBegin("io_test"));
      CpuOpcode decAbs = cpu.getOpcode("dec", CpuAddressingMode.ABS, false);
      CpuOpcode cmpAbs = cpu.getOpcode("cmp", CpuAddressingMode.ABS, false);
      ioChunk.addLine(new AsmInstruction(decAbs, "$d020", null, null));
      ioChunk.addLine(new AsmInstruction(cmpAbs, "$d020", null, null));
      ioChunk.addLine(new AsmScopeEnd());

      // Part 2: Aliased ZP labels should fold dec.z alias; cmp.z orig into dcp.z
      AsmChunk aliasChunk = asm.startChunk(ScopeRef.ROOT, null, "alias_test");
      aliasChunk.addLine(new AsmScopeBegin("alias_test"));
      aliasChunk.addLine(new AsmLabelDecl("orig", "$02"));
      aliasChunk.addLine(new AsmLabelDecl("alias", "orig"));
      CpuOpcode decZp = cpu.getOpcode("dec", CpuAddressingMode.ZP, false);
      CpuOpcode cmpZp = cpu.getOpcode("cmp", CpuAddressingMode.ZP, false);
      aliasChunk.addLine(new AsmInstruction(decZp, "alias", null, null));
      aliasChunk.addLine(new AsmInstruction(cmpZp, "orig", null, null));
      aliasChunk.addLine(new AsmScopeEnd());

      new Pass5IllegalOpcodeOptimization(program).optimize();
      String out = asm.toString();
      assertFalse(out.contains("dcp $d020"),
            "RMW illegal DCP must NOT be generated on hardware I/O address $d020! ASM:\n" + out);
      assertTrue(out.contains("dcp.z alias"),
            "RMW illegal DCP should fold aliased ZP operands (alias == orig)! ASM:\n" + out);
   }

   @Test
   void testIll03_UndocFragmentsFixed() throws Exception {
      Path rol7 = Paths.get("src/main/fragment/mos6502-undoc/vbuaa=vbuaa_rol_7.asm");
      String rol7Content = Files.readString(rol7).trim();
      assertFalse(rol7Content.contains("anc #$80"), "vbuaa=vbuaa_rol_7.asm must not use broken anc #$80; asl");
      assertTrue(rol7Content.contains("alr #$01") && rol7Content.contains("ror"),
            "vbuaa=vbuaa_rol_7.asm should use alr #$01; ror, got:\n" + rol7Content);

      Path negA = Paths.get("src/main/fragment/mos6502-undoc/vbuxx=_neg_vbuaa.asm");
      String negAContent = Files.readString(negA).trim();
      assertFalse(negAContent.contains("axs #-$01-1"), "vbuxx=_neg_vbuaa.asm must not have off-by-one axs #-$01-1");
      assertTrue(negAContent.contains("axs #-1"),
            "vbuxx=_neg_vbuaa.asm should use axs #-1, got:\n" + negAContent);

      assertFalse(Files.exists(Paths.get("src/main/fragment/mos6502-undoc/vbuxx=vbuxx_minus_2.asm")),
            "Pessimal mos6502-undoc/vbuxx=vbuxx_minus_2.asm should be removed");
      assertFalse(Files.exists(Paths.get("src/main/fragment/mos6502-undoc/vbsxx=vbsxx_minus_2.asm")),
            "Pessimal mos6502-undoc/vbsxx=vbsxx_minus_2.asm should be removed");
   }
}
