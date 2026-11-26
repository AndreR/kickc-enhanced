package dk.camelot64.kickc.test;

import dk.camelot64.cpufamily6502.Cpu65xx;
import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.Compiler;
import dk.camelot64.kickc.TmpDirManager;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.TargetPlatform;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.parser.CTargetPlatformParser;
import dk.camelot64.kickc.passes.Pass5AxRegisterCallingConventionOptimization;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests ensuring that Pass5AxRegisterCallingConventionOptimization (Auto-AX ABI)
 * never introduces an X or A register clobber in a callee's return sequence when X or A is
 * live across a direct or transitive call site in any caller.
 */
public class TestReturnClobberX {

   @BeforeAll
   static void setup() {
      TmpDirManager.init(new File("").toPath());
   }

   private Program compileC(String source) throws Exception {
      Path tempFile = Files.createTempFile("kickc_ret_clobber_x_", ".c");
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

   private static String extractScopeAsm(String fullAsm, String procName) {
      int start = fullAsm.indexOf(procName + ":");
      assertTrue(start >= 0, "Expected procedure " + procName + " in ASM:\n" + fullAsm);
      int end = fullAsm.indexOf("\n}", start);
      assertTrue(end >= 0, "Expected end of scope for " + procName + " in ASM:\n" + fullAsm);
      return fullAsm.substring(start, end + 2);
   }

   private static boolean scopeModifiesX(String procAsm) {
      for (String rawLine : procAsm.split("\\R")) {
         String line = rawLine.trim().toLowerCase();
         if (line.isEmpty() || line.startsWith("//") || line.startsWith(".")) continue;
         if (line.startsWith("ldx") || line.startsWith("tax") || line.startsWith("tsx")
               || line.startsWith("lax") || line.startsWith("inx") || line.startsWith("dex")
               || line.startsWith("plx")) {
            return true;
         }
      }
      return false;
   }

   @Test
   void testTransitiveCallerLiveXAcrossMiddleToLeafReturn() throws Exception {
      // caller() keeps loop/live byte in X across jsr middle().
      // middle() calls uint16_t leaf(acc, data, len) whose body uses only A and Y and returns a ZP word.
      // Either caller() must NOT hold a live variable in X across jsr middle(),
      // OR leaf() (and middle()) must NOT clobber X (e.g., via Pass5 Auto-AX ldx.z return+1).
      String src = """
            #include <stdint.h>

            volatile uint16_t out_sum = 0;
            volatile uint8_t out_tag = 0;
            const uint8_t table_a[4] = { 10, 20, 30, 40 };
            const uint8_t table_b[4] = { 1, 2, 3, 4 };
            volatile uint8_t count_v = 4;

            uint16_t leaf(uint16_t acc, const uint8_t* data, uint16_t len) {
                uint8_t n = (uint8_t)len;
                for (uint8_t i = 0; i < n; i++) {
                    acc += data[i];
                }
                return acc;
            }

            void middle(const uint8_t* ptr) {
                out_sum = leaf(out_sum, ptr, count_v);
                out_sum = leaf(out_sum, ptr, count_v);
            }

            void caller(uint8_t n) {
                for (uint8_t idx = 0; idx < n; idx++) {
                    uint8_t tag = idx * 3 + 1;
                    middle(table_a);
                    out_tag = tag;
                    middle(table_b);
                }
            }

            void main(void) {
                caller(count_v);
                caller(count_v);
            }
            """;

      Program program = compileC(src);
      String fullAsm = program.getAsm().toString();
      String callerAsm = extractScopeAsm(fullAsm, "caller");
      String middleAsm = extractScopeAsm(fullAsm, "middle");
      String leafAsm = extractScopeAsm(fullAsm, "leaf");

      // Check if caller holds a variable in X across `jsr middle`
      boolean callerUsesXAcrossMiddle = false;
      String[] lines = callerAsm.split("\\R");
      int jsrIdx = -1;
      for (int i = 0; i < lines.length; i++) {
         if (lines[i].trim().toLowerCase().startsWith("jsr middle")) {
            jsrIdx = i;
            break;
         }
      }
      assertTrue(jsrIdx >= 0, "Expected jsr middle in caller:\n" + callerAsm);

      // Check if caller reads X after jsr middle before overwriting X
      for (int i = jsrIdx + 1; i < lines.length; i++) {
         String l = lines[i].trim().toLowerCase();
         if (l.isEmpty() || l.startsWith("//") || l.startsWith(".")) continue;
         if (l.startsWith("ldx") || l.startsWith("tax") || l.startsWith("tsx") || l.startsWith("lax") || l.startsWith("plx")) {
            break;
         }
         if (l.startsWith("stx") || l.startsWith("cpx") || l.startsWith("inx") || l.startsWith("dex")
               || l.startsWith("txa") || l.startsWith("txs") || l.contains(",x") || l.startsWith("sax") || l.startsWith("axs")) {
            callerUsesXAcrossMiddle = true;
            break;
         }
      }
      // Also check loop backedge in caller (e.g. idx held in X across jsr middle, tested by cpx/inx at loop header)
      if (!callerUsesXAcrossMiddle && Pattern.compile("(?m)^\\s*(ldx|inx|dex)\\b").matcher(callerAsm).find()
            && Pattern.compile("(?m)^\\s*(stx|cpx|inx|dex|txa)\\b").matcher(callerAsm).find()) {
         // Verify whether X is overwritten right after jsr middle on all paths
         boolean overwrittenAfterJsr = false;
         for (int i = jsrIdx + 1; i < lines.length; i++) {
            String l = lines[i].trim().toLowerCase();
            if (l.startsWith("ldx") || l.startsWith("tax") || l.startsWith("lax")) {
               overwrittenAfterJsr = true;
               break;
            }
            if (l.startsWith("jmp") || l.startsWith("rts") || l.startsWith("b")) {
               break;
            }
         }
         if (!overwrittenAfterJsr) {
            callerUsesXAcrossMiddle = true;
         }
      }

      if (callerUsesXAcrossMiddle) {
         assertFalse(scopeModifiesX(leafAsm),
               "leaf() must NOT clobber X when caller() keeps a live variable in X across jsr middle()!\n"
                     + "caller:\n" + callerAsm + "\nmiddle:\n" + middleAsm + "\nleaf:\n" + leafAsm);
         assertFalse(scopeModifiesX(middleAsm),
               "middle() must NOT clobber X when caller() keeps a live variable in X across jsr middle()!\n"
                     + "caller:\n" + callerAsm + "\nmiddle:\n" + middleAsm + "\nleaf:\n" + leafAsm);
      }
   }

   @Test
   void testPass5AxRejectsTransitiveLiveXAndLiveA() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // Case 1: caller holds X live across jsr middle; middle calls jsr leaf (doesn't touch X) -> leaf MUST NOT use AX return
      Program p1 = new Program();
      AsmProgram asm1 = new AsmProgram(TargetCpu.MOS6502X);
      p1.setAsm(asm1);

      AsmChunk cLeaf1 = asm1.startChunk(ScopeRef.ROOT, null, "leaf");
      cLeaf1.addLine(new AsmLabel("leaf"));
      cLeaf1.addLine(new AsmScopeBegin("leaf"));
      cLeaf1.addLine(new AsmLabelDecl("return", "$18"));
      cLeaf1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      cLeaf1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cLeaf1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "2", null, null));
      cLeaf1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return+1", null, null));
      cLeaf1.addLine(new AsmLabel("__breturn"));
      cLeaf1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cLeaf1.addLine(new AsmScopeEnd());

      AsmChunk cMid1 = asm1.startChunk(ScopeRef.ROOT, null, "middle");
      cMid1.addLine(new AsmLabel("middle"));
      cMid1.addLine(new AsmScopeBegin("middle"));
      cMid1.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "leaf", null, null));
      cMid1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return", null, null));
      cMid1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$20", null, null));
      cMid1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return+1", null, null));
      cMid1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$21", null, null));
      cMid1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cMid1.addLine(new AsmScopeEnd());

      AsmChunk cCaller1 = asm1.startChunk(ScopeRef.ROOT, null, "caller");
      cCaller1.addLine(new AsmLabel("caller"));
      cCaller1.addLine(new AsmScopeBegin("caller"));
      cCaller1.addLine(new AsmInstruction(cpu.getOpcode("ldx", CpuAddressingMode.ZP, false), "$30", null, null));
      cCaller1.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "middle", null, null));
      cCaller1.addLine(new AsmInstruction(cpu.getOpcode("stx", CpuAddressingMode.ZP, false), "$31", null, null));
      cCaller1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCaller1.addLine(new AsmScopeEnd());

      // Register procedures in Scope so Pass5Ax sees leaf as returning 2 bytes
      Procedure leafProc = new Procedure("leaf", new SymbolTypeProcedure(SymbolType.WORD, Collections.emptyList()),
            p1.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      leafProc.setParameters(Collections.emptyList());
      p1.getScope().add(leafProc);
      Procedure midProc = new Procedure("middle", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p1.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      midProc.setParameters(Collections.emptyList());
      p1.getScope().add(midProc);
      Procedure callerProc = new Procedure("caller", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p1.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      callerProc.setParameters(Collections.emptyList());
      p1.getScope().add(callerProc);

      assertFalse(new Pass5AxRegisterCallingConventionOptimization(p1).optimize(),
            "Must NOT convert leaf() to AX return when caller() holds live X across jsr middle()! ASM:\n" + asm1);
      String leaf1Asm = extractScopeAsm(asm1.toString(), "leaf");
      assertFalse(leaf1Asm.contains("ldx"),
            "leaf() must NOT emit ldx.z $18+1! ASM:\n" + leaf1Asm);

      // Case 2: Safe case — nobody holds X or A live across leaf/middle -> Auto-AX optimization MUST still apply!
      Program p2 = new Program();
      AsmProgram asm2 = new AsmProgram(TargetCpu.MOS6502X);
      p2.setAsm(asm2);

      AsmChunk cLeaf2 = asm2.startChunk(ScopeRef.ROOT, null, "leaf");
      cLeaf2.addLine(new AsmLabel("leaf"));
      cLeaf2.addLine(new AsmScopeBegin("leaf"));
      cLeaf2.addLine(new AsmLabelDecl("return", "$18"));
      cLeaf2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      cLeaf2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cLeaf2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "2", null, null));
      cLeaf2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return+1", null, null));
      cLeaf2.addLine(new AsmLabel("__breturn"));
      cLeaf2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cLeaf2.addLine(new AsmScopeEnd());

      AsmChunk cMid2 = asm2.startChunk(ScopeRef.ROOT, null, "middle");
      cMid2.addLine(new AsmLabel("middle"));
      cMid2.addLine(new AsmScopeBegin("middle"));
      cMid2.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "leaf", null, null));
      cMid2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return", null, null));
      cMid2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$20", null, null));
      cMid2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return+1", null, null));
      cMid2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$21", null, null));
      cMid2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cMid2.addLine(new AsmScopeEnd());

      AsmChunk cCaller2 = asm2.startChunk(ScopeRef.ROOT, null, "caller");
      cCaller2.addLine(new AsmLabel("caller"));
      cCaller2.addLine(new AsmScopeBegin("caller"));
      cCaller2.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "middle", null, null));
      cCaller2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCaller2.addLine(new AsmScopeEnd());

      Procedure leafProc2 = new Procedure("leaf", new SymbolTypeProcedure(SymbolType.WORD, Collections.emptyList()),
            p2.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      leafProc2.setParameters(Collections.emptyList());
      p2.getScope().add(leafProc2);
      Procedure midProc2 = new Procedure("middle", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p2.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      midProc2.setParameters(Collections.emptyList());
      p2.getScope().add(midProc2);
      Procedure callerProc2 = new Procedure("caller", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p2.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      callerProc2.setParameters(Collections.emptyList());
      p2.getScope().add(callerProc2);

      assertTrue(new Pass5AxRegisterCallingConventionOptimization(p2).optimize(),
            "Safe case where X/A are not live across middle/leaf should still optimize to AX!");
   }

   @Test
   void testPass5AxRejectsTransitiveLiveAAndHandlesMultiCallerAndChainedAx() {
      Cpu65xx cpu = TargetCpu.MOS6502X.getCpu65xx();

      // Case 3: caller holds A live across jsr middle; middle ignores leaf's return and doesn't overwrite A;
      // leaf only touches Y and returns via __breturn: rts -> leaf MUST NOT clobber A!
      Program p3 = new Program();
      AsmProgram asm3 = new AsmProgram(TargetCpu.MOS6502X);
      p3.setAsm(asm3);

      AsmChunk cLeaf3 = asm3.startChunk(ScopeRef.ROOT, null, "leaf");
      cLeaf3.addLine(new AsmLabel("leaf"));
      cLeaf3.addLine(new AsmScopeBegin("leaf"));
      cLeaf3.addLine(new AsmLabelDecl("return", "$18"));
      cLeaf3.addLine(new AsmInstruction(cpu.getOpcode("ldy", CpuAddressingMode.IMM, false), "1", null, null));
      cLeaf3.addLine(new AsmInstruction(cpu.getOpcode("sty", CpuAddressingMode.ZP, false), "return", null, null));
      cLeaf3.addLine(new AsmLabel("__breturn"));
      cLeaf3.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cLeaf3.addLine(new AsmScopeEnd());

      AsmChunk cMid3 = asm3.startChunk(ScopeRef.ROOT, null, "middle");
      cMid3.addLine(new AsmLabel("middle"));
      cMid3.addLine(new AsmScopeBegin("middle"));
      cMid3.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "leaf", null, null));
      cMid3.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cMid3.addLine(new AsmScopeEnd());

      AsmChunk cCaller3 = asm3.startChunk(ScopeRef.ROOT, null, "caller");
      cCaller3.addLine(new AsmLabel("caller"));
      cCaller3.addLine(new AsmScopeBegin("caller"));
      cCaller3.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "$30", null, null));
      cCaller3.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "middle", null, null));
      cCaller3.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$31", null, null));
      cCaller3.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cCaller3.addLine(new AsmScopeEnd());

      Procedure leaf3 = new Procedure("leaf", new SymbolTypeProcedure(SymbolType.WORD, Collections.emptyList()),
            p3.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      leaf3.setParameters(Collections.emptyList());
      p3.getScope().add(leaf3);
      Procedure mid3 = new Procedure("middle", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p3.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      mid3.setParameters(Collections.emptyList());
      p3.getScope().add(mid3);
      Procedure caller3 = new Procedure("caller", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p3.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      caller3.setParameters(Collections.emptyList());
      p3.getScope().add(caller3);

      assertFalse(new Pass5AxRegisterCallingConventionOptimization(p3).optimize(),
            "Must NOT convert leaf() to AX return when caller() holds live A across jsr middle()! ASM:\n" + asm3);
      String leaf3Asm = extractScopeAsm(asm3.toString(), "leaf");
      assertFalse(leaf3Asm.contains("lda") || leaf3Asm.contains("ldx"),
            "leaf() must NOT emit lda/ldx! ASM:\n" + leaf3Asm);

      // Case 4: Case 1B callee with TWO callers -> BOTH callers must be patched (not abandoned after patching first caller)
      Program p4 = new Program();
      AsmProgram asm4 = new AsmProgram(TargetCpu.MOS6502X);
      p4.setAsm(asm4);

      AsmChunk cLeaf4 = asm4.startChunk(ScopeRef.ROOT, null, "leaf");
      cLeaf4.addLine(new AsmLabel("leaf"));
      cLeaf4.addLine(new AsmScopeBegin("leaf"));
      cLeaf4.addLine(new AsmLabelDecl("return", "$18"));
      cLeaf4.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      cLeaf4.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cLeaf4.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "2", null, null));
      cLeaf4.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return+1", null, null));
      cLeaf4.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cLeaf4.addLine(new AsmScopeEnd());

      AsmChunk c1 = asm4.startChunk(ScopeRef.ROOT, null, "caller1");
      c1.addLine(new AsmLabel("caller1"));
      c1.addLine(new AsmScopeBegin("caller1"));
      c1.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "leaf", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$20", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return+1", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$21", null, null));
      c1.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c1.addLine(new AsmScopeEnd());

      AsmChunk c2 = asm4.startChunk(ScopeRef.ROOT, null, "caller2");
      c2.addLine(new AsmLabel("caller2"));
      c2.addLine(new AsmScopeBegin("caller2"));
      c2.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "leaf", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$22", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return+1", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "$23", null, null));
      c2.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      c2.addLine(new AsmScopeEnd());

      Procedure leaf4 = new Procedure("leaf", new SymbolTypeProcedure(SymbolType.WORD, Collections.emptyList()),
            p4.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      leaf4.setParameters(Collections.emptyList());
      p4.getScope().add(leaf4);
      Procedure p4c1 = new Procedure("caller1", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p4.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      p4c1.setParameters(Collections.emptyList());
      p4.getScope().add(p4c1);
      Procedure p4c2 = new Procedure("caller2", new SymbolTypeProcedure(SymbolType.VOID, Collections.emptyList()),
            p4.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      p4c2.setParameters(Collections.emptyList());
      p4.getScope().add(p4c2);

      assertTrue(new Pass5AxRegisterCallingConventionOptimization(p4).optimize());
      String asm4Str = asm4.toString();
      assertTrue(asm4Str.contains("stx.z $21") && asm4Str.contains("stx.z $23"),
            "Both caller1 and caller2 must be patched to consume AX return! ASM:\n" + asm4Str);
      assertFalse(asm4Str.contains("leaf.return"),
            "No stale leaf.return loads should remain! ASM:\n" + asm4Str);

      // Case 5: Chained AX return where middle directly returns leaf() -> must NOT emit spurious `tax`
      Program p5 = new Program();
      AsmProgram asm5 = new AsmProgram(TargetCpu.MOS6502X);
      p5.setAsm(asm5);

      AsmChunk cLeaf5 = asm5.startChunk(ScopeRef.ROOT, null, "leaf");
      cLeaf5.addLine(new AsmLabel("leaf"));
      cLeaf5.addLine(new AsmScopeBegin("leaf"));
      cLeaf5.addLine(new AsmLabelDecl("return", "$18"));
      cLeaf5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "1", null, null));
      cLeaf5.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cLeaf5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.IMM, false), "2", null, null));
      cLeaf5.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return+1", null, null));
      cLeaf5.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cLeaf5.addLine(new AsmScopeEnd());

      AsmChunk cMid5 = asm5.startChunk(ScopeRef.ROOT, null, "middle");
      cMid5.addLine(new AsmLabel("middle"));
      cMid5.addLine(new AsmScopeBegin("middle"));
      cMid5.addLine(new AsmLabelDecl("return", "$1a"));
      cMid5.addLine(new AsmInstruction(cpu.getOpcode("jsr", CpuAddressingMode.ABS, false), "leaf", null, null));
      cMid5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return", null, null));
      cMid5.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return", null, null));
      cMid5.addLine(new AsmInstruction(cpu.getOpcode("lda", CpuAddressingMode.ZP, false), "leaf.return+1", null, null));
      cMid5.addLine(new AsmInstruction(cpu.getOpcode("sta", CpuAddressingMode.ZP, false), "return+1", null, null));
      cMid5.addLine(new AsmInstruction(cpu.getOpcode("rts", CpuAddressingMode.NON, false), null, null, null));
      cMid5.addLine(new AsmScopeEnd());

      Procedure leaf5 = new Procedure("leaf", new SymbolTypeProcedure(SymbolType.WORD, Collections.emptyList()),
            p5.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      leaf5.setParameters(Collections.emptyList());
      p5.getScope().add(leaf5);
      Procedure mid5 = new Procedure("middle", new SymbolTypeProcedure(SymbolType.WORD, Collections.emptyList()),
            p5.getScope(), "Code", "Data", Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
      mid5.setParameters(Collections.emptyList());
      p5.getScope().add(mid5);

      assertTrue(new Pass5AxRegisterCallingConventionOptimization(p5).optimize());
      String mid5Asm = extractScopeAsm(asm5.toString(), "middle");
      assertFalse(mid5Asm.contains("tax"),
            "Chained AX return in middle() must NOT emit spurious `tax` after `stx.z return+1`! ASM:\n" + mid5Asm);
   }
}
