package dk.camelot64.kickc.test;

import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import kickass.KickAssembler65CE02;
import kickass.nonasm.c64.CharToPetsciiConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Issue #62: Selective 6502 Bytecode VM code generation
 * ({@code __bytecode} / {@code #pragma bytecode(on/off)}) via {@link dk.camelot64.kickc.passes.Pass4BytecodeGenerator}.
 */
public class TestBytecodeVm {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_bc_vm_", ".c");
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

   private static void assembleWithKickAss(Program program, String asmText) throws Exception {
      Path tmpDir = TmpDirManager.MANAGER.newTmpDir();
      String prgName = program.getOutputFileManager().getBinaryOutputFile().getFileName().toString();
      File asmFile = new File(tmpDir.toFile(), "bytecode_test.asm");
      File prgFile = new File(tmpDir.toFile(), prgName);
      Files.writeString(asmFile.toPath(), asmText);
      ByteArrayOutputStream kickAssOut = new ByteArrayOutputStream();
      PrintStream oldOut = System.out;
      System.setOut(new PrintStream(kickAssOut));
      try {
         CharToPetsciiConverter.setCurrentEncoding("screencode_mixed");
         KickAssembler65CE02.main2(new String[]{asmFile.getAbsolutePath(), "-odir", tmpDir.toAbsolutePath().toString()});
      } catch(Throwable t) {
         System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
         fail("KickAssembler failed:\n" + kickAssOut + "\nASM:\n" + asmText, t);
      } finally {
         System.setOut(oldOut);
      }
      assertTrue(prgFile.exists() && prgFile.length() > 0, "Expected assembled PRG output " + prgFile + ". KickAss output:\n" + kickAssOut);
   }

   private static int countOccurrences(String haystack, String needle) {
      int count = 0;
      int idx = 0;
      while((idx = haystack.indexOf(needle, idx)) != -1) {
         count++;
         idx += needle.length();
      }
      return count;
   }

   @Test
   void testBytecodeProcedureEmitsStubStreamSingleVmAndAssembles() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t state = 0;
            volatile uint8_t * const IN_PORT = (uint8_t *)0x0400;
            volatile uint8_t * const OUT_PORT = (uint8_t *)0x0401;

            void native_helper(void) {
                state = state + 10;
            }

            __bytecode void menu_logic(void) {
                uint8_t a = *IN_PORT;
                uint8_t b = a + 5;
                uint8_t c = b + 2;
                if (c != 0) {
                    native_helper();
                }
                *OUT_PORT = c;
            }

            void main(void) {
                menu_logic();
                menu_logic();
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      String asmLower = asm.toLowerCase(Locale.ROOT);

      assertTrue(asmLower.contains("menu_logic:"), "Expected menu_logic scope in ASM:\n" + asm);
      assertTrue(asmLower.contains("jmp __bc_vm_exec"), "Expected jmp __bc_vm_exec stub in ASM:\n" + asm);
      assertTrue(asmLower.contains("__bc_stream_menu_logic:"), "Expected bytecode stream label:\n" + asm);
      assertTrue(asmLower.contains(".byte $01") || asmLower.contains(".byte $02") || asmLower.contains(".byte $06"),
            "Expected VM .byte stream opcodes:\n" + asm);
      assertEquals(1, countOccurrences(asmLower, "__bc_vm_exec:"),
            "Expected shared VM interpreter emitted exactly once:\n" + asm);

      assembleWithKickAss(program, asm);
   }

   @Test
   void testPragmaBytecodeSelectiveActivationAndNativeOutside() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const OUT = (uint8_t *)0x0400;
            volatile uint8_t flag = 1;

            void native_before(void) {
                OUT[0] = 1;
            }

            #pragma bytecode(on)
            void bc_enclosed(void) {
                uint8_t v = flag + 3;
                if (v == 4) {
                    native_before();
                }
                OUT[1] = v;
            }
            #pragma bytecode(off)

            void native_after(void) {
                OUT[2] = 2;
            }

            void main(void) {
                native_before();
                bc_enclosed();
                bc_enclosed();
                native_after();
            }
            """;
      Program program = compileC(code);
      String asm = program.getAsm().toString();
      String asmLower = asm.toLowerCase(Locale.ROOT);

      assertTrue(asmLower.contains("__bc_stream_bc_enclosed:"), "Expected bytecode stream for pragma-enclosed proc:\n" + asm);
      assertFalse(asmLower.contains("__bc_stream_native_before"), "native_before must remain native 6502");
      assertFalse(asmLower.contains("__bc_stream_native_after"), "native_after must remain native 6502");
      assertEquals(1, countOccurrences(asmLower, "__bc_vm_exec:"), "Expected single shared VM runtime");

      assembleWithKickAss(program, asm);
   }

   @Test
   void testProgramWithoutBytecodeEmitsZeroVmOverhead() throws Exception {
      String code = """
            #include <stdint.h>
            volatile uint8_t * const OUT = (uint8_t *)0x0400;

            void main(void) {
                *OUT = 42;
            }
            """;
      Program program = compileC(code);
      String asmLower = program.getAsm().toString().toLowerCase(Locale.ROOT);
      assertFalse(asmLower.contains("__bc_vm_exec"), "Program without __bytecode must emit 0 bytes of VM runtime:\n" + asmLower);
      assertFalse(asmLower.contains("__bc_stream_"), "Program without __bytecode must not emit bytecode streams:\n" + asmLower);
   }
}
