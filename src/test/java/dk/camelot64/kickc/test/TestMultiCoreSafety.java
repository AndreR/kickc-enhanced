package dk.camelot64.kickc.test;

import dk.camelot64.kickc.CompileLog;
import dk.camelot64.kickc.fragment.AsmFragmentClobber;
import dk.camelot64.kickc.fragment.AsmFragmentTemplate;
import dk.camelot64.kickc.fragment.AsmFragmentTemplateUsages;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentSynthesisResult;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateSynthesizer;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.StringEncoding;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.Pass2MultiplyToShiftRewriting;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency and isolation tests for multi-core safety fixes (MC-1, MC-2, MC-3, MC-8).
 */
public class TestMultiCoreSafety {

   @Test
   void testProgramScopeConcurrentLookup() throws Exception {
      ProgramScope scope = new ProgramScope();
      int count = 200;
      for(int i = 0; i < count; i++) {
         Variable v = new Variable("var_" + i, Variable.Kind.LOAD_STORE, SymbolType.BYTE, scope, Variable.MemoryArea.ZEROPAGE_MEMORY, null, null);
         scope.add(v);
      }

      int threads = 8;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
         List<Callable<Void>> tasks = new ArrayList<>();
         for(int t = 0; t < threads; t++) {
            tasks.add(() -> {
               for(int round = 0; round < 5; round++) {
                  for(int i = 0; i < count; i++) {
                     Symbol s = scope.getGlobalSymbol("var_" + i);
                     assertNotNull(s);
                  }
               }
               return null;
            });
         }
         for(Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
         }
      } finally {
         pool.shutdownNow();
      }
   }

   @Test
   void testAsmFragmentTemplateConcurrentInitWithThreadOverride() throws Exception {
      AsmFragmentTemplate template = new AsmFragmentTemplate("vbuz1=vbuc1", "lda #{c1}\nsta {z1}", TargetCpu.MOS6502X);
      VariableRef conflictingVar = new VariableRef("z1");

      int threads = 8;
      CountDownLatch startLatch = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
         List<Future<AsmFragmentClobber>> futures = new ArrayList<>();
         for(int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
               Map<VariableRef, Registers.Register> override = Collections.singletonMap(conflictingVar, Registers.getRegisterA());
               Variable.setThreadAllocationOverride(override);
               try {
                  startLatch.await();
                  double cycles = template.getCycles();
                  assertTrue(cycles > 0);
                  AsmFragmentClobber clobber = template.getClobber();
                  assertNotNull(clobber);
                  assertTrue(clobber.isClobberA());
                  // Ensure thread's own override was restored after initAsm()
                  assertSame(override, Variable.getThreadAllocationOverride());
                  return clobber;
               } finally {
                  Variable.setThreadAllocationOverride(null);
               }
            }));
         }
         startLatch.countDown();
         for(Future<AsmFragmentClobber> f : futures) {
            assertNotNull(f.get());
         }
      } finally {
         pool.shutdownNow();
      }
   }

   @Test
   void testStringEncodingPerProgramIsolation() {
      Program p1 = new Program();
      Program p2 = new Program();

      Map<Character, Byte> map1 = Collections.singletonMap('A', (byte) 0x41);
      StringEncoding custom1 = StringEncoding.createCustom("custom_enc", StringEncoding.SCREENCODE_MIXED, "custom_enc", map1);
      p1.registerCustomStringEncoding(custom1);

      assertSame(custom1, StringEncoding.fromName("custom_enc", p1));
      assertSame(custom1, StringEncoding.fromSuffix("custom_enc", StringEncoding.SCREENCODE_MIXED, p1));
      assertThrows(IllegalArgumentException.class, () -> StringEncoding.fromName("custom_enc", p2));
      assertThrows(IllegalArgumentException.class, () -> StringEncoding.fromName("custom_enc"));
   }

   @Test
   void testAsmFragmentUsagesPerSynthesizerIsolation() {
      CompileLog log = new CompileLog();
      AsmFragmentTemplateSynthesizer s1 = new AsmFragmentTemplateSynthesizer(TargetCpu.MOS6502X, null, Paths.get("src/main/fragment"), log);
      AsmFragmentTemplateSynthesizer s2 = new AsmFragmentTemplateSynthesizer(TargetCpu.MOS6502X, null, Paths.get("src/main/fragment"), log);

      AsmFragmentTemplate t = new AsmFragmentTemplate("vbuz1=vbuc1", "lda #{c1}\nsta {z1}", TargetCpu.MOS6502X);
      AsmFragmentSynthesisResult r = new AsmFragmentSynthesisResult(t, true, false, null, Collections.emptyList());

      s1.getUsages().incUsage(r);
      assertEquals(1, s1.getUsages().getUsage(r));
      assertEquals(0, s2.getUsages().getUsage(r));
      assertNotSame(s1.getUsages(), s2.getUsages());
   }

   @Test
   void testRecipCacheConcurrentAccess() throws Exception {
      int threads = 8;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
         List<Callable<Void>> tasks = new ArrayList<>();
         for(int t = 0; t < threads; t++) {
            tasks.add(() -> {
               for(long d = 2; d <= 255; d++) {
                  Pass2MultiplyToShiftRewriting.Reciprocal r = Pass2MultiplyToShiftRewriting.findReciprocal(d);
                  if(r != null) {
                     assertTrue(r.s >= 8 && r.s <= 23);
                  }
               }
               return null;
            });
         }
         for(Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
         }
      } finally {
         pool.shutdownNow();
      }
   }
}
