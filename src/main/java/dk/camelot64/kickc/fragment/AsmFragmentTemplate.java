package dk.camelot64.kickc.fragment;

import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.Registers;
import dk.camelot64.kickc.model.TargetCpu;
import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.Value;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.parser.AsmParser;
import dk.camelot64.kickc.parser.KickCParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An ASM fragment template usable for generating KickAssembler code for different bindings.
 * The AsmFragmentTemplateSynthesizer can generate multiple different templates usable for a specific fragment signature.
 */
public class AsmFragmentTemplate {

   /** The fragment template signature name. */
   private final String signature;
   /** The target CPU. */
   private final TargetCpu targetCpu;
   /** The fragment template body */
   private String body;

   /** The parsed ASM lines. Initially null. Non-null after the template is used to generate ASM code. */
   private volatile KickCParser.AsmLinesContext bodyAsm;
   /** The ASM clobber of the fragment. Initially null. Non-null after the template is used to generate ASM code.*/
   private volatile AsmFragmentClobber clobber;
   /** The cycles consumed by the ASM of the fragment. Initially null. Non-null after the template is used to generate ASM code.*/
   private volatile Double cycles;
   /** The bytes consumed by the ASM of the fragment. Initially -1. Non-negative after the template is used to generate ASM code.*/
   private volatile int bytes = -1;

   public AsmFragmentTemplate(String signature, String body, TargetCpu targetCpu) {
      this.signature = signature.intern();
      this.body = body;
      this.targetCpu = targetCpu;
   }

   /**
    * Creates an inline ASM fragment template
    *
    * @param bodyLines Parsed ASM body
    */
   public AsmFragmentTemplate(KickCParser.AsmLinesContext bodyLines, TargetCpu targetCpu) {
      this.signature = "--inline--";
      this.bodyAsm = bodyLines;
      this.targetCpu = targetCpu;
   }

   /**
    * Create a load/store variable
    *
    * @param name The name
    * @param type The type
    * @param scope The scope
    * @param memoryArea The memory area (zeropage/main memory)
    * @param dataSegment The data segment (in main memory)
    * @return The new PHI-master variable
    */
   public static Variable createLoadStore(String name, SymbolType type, Scope scope, Variable.MemoryArea memoryArea, String dataSegment) {
      return new Variable(name, Variable.Kind.LOAD_STORE, type, scope, memoryArea, dataSegment, null);
   }

   /**
    * Create a PHI master variable
    *
    * @param name The name
    * @param type The type
    * @param scope The scope
    * @param memoryArea The memory area (zeropage/main memory)
    * @param dataSegment The data segment (in main memory)
    * @return The new PHI-master variable
    */
   public static Variable createPhiMaster(String name, SymbolType type, Scope scope, Variable.MemoryArea memoryArea, String dataSegment) {
      return new Variable(name, Variable.Kind.PHI_MASTER, type, scope, memoryArea, dataSegment, null);
   }

   /**
    * Initialize the fields that require parsing the ASM (bodyAsm, clobber, cycles, bytes).
    */
   private synchronized void initAsm() {
      if(this.bodyAsm != null && this.clobber != null && this.cycles != null && this.bytes >= 0) {
         return;
      }
      Map<VariableRef, Registers.Register> prevOverride = Variable.getThreadAllocationOverride();
      if(prevOverride != null) {
         Variable.setThreadAllocationOverride(null);
      }
      try {
         // Parse the body ASM into a local first; publish volatile bodyAsm LAST so readers
         // observing bodyAsm != null are guaranteed to see initialized clobber and cycles (MC-3).
         KickCParser.AsmLinesContext parsedBodyAsm = AsmParser.parseAsm(this.body, new StatementSource(signature + ".asm", 1, 1, this.body, -1, -1));
         // Generate a dummy instance to find clobber & cycles
         ProgramScope scope = new ProgramScope();
         LinkedHashMap<String, Value> bindings = new LinkedHashMap<>();
         if(signature.contains("z1") || signature.contains("z2") || signature.contains("z3")
               || signature.contains("z4") || signature.contains("z5") || signature.contains("z6")) {
            Variable master = createPhiMaster("z", SymbolType.BYTE, scope, Variable.MemoryArea.ZEROPAGE_MEMORY, null);
            if(signature.contains("z1")) { Variable v = Variable.createPhiVersion(master, 1); v.setName("z1"); v.setAllocation(new Registers.RegisterZpMem(2, 1)); bindings.put("z1", v); }
            if(signature.contains("z2")) { Variable v = Variable.createPhiVersion(master, 2); v.setName("z2"); v.setAllocation(new Registers.RegisterZpMem(4, 1)); bindings.put("z2", v); }
            if(signature.contains("z3")) { Variable v = Variable.createPhiVersion(master, 3); v.setName("z3"); v.setAllocation(new Registers.RegisterZpMem(6, 1)); bindings.put("z3", v); }
            if(signature.contains("z4")) { Variable v = Variable.createPhiVersion(master, 4); v.setName("z4"); v.setAllocation(new Registers.RegisterZpMem(8, 1)); bindings.put("z4", v); }
            if(signature.contains("z5")) { Variable v = Variable.createPhiVersion(master, 5); v.setName("z5"); v.setAllocation(new Registers.RegisterZpMem(9, 1)); bindings.put("z5", v); }
            if(signature.contains("z6")) { Variable v = Variable.createPhiVersion(master, 6); v.setName("z6"); v.setAllocation(new Registers.RegisterZpMem(10, 1)); bindings.put("z6", v); }
         }
         if(signature.contains("m1") || signature.contains("m2") || signature.contains("m3")
               || signature.contains("m4") || signature.contains("m5") || signature.contains("m6")) {
            if(signature.contains("m1")) { Variable v = createLoadStore("m1", SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, null); v.setAllocation(new Registers.RegisterMainMem(v.getVariableRef(), 1, null, false)); bindings.put("m1", v); }
            if(signature.contains("m2")) { Variable v = createLoadStore("m2", SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, null); v.setAllocation(new Registers.RegisterMainMem(v.getVariableRef(), 1, null, false)); bindings.put("m2", v); }
            if(signature.contains("m3")) { Variable v = createLoadStore("m3", SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, null); v.setAllocation(new Registers.RegisterMainMem(v.getVariableRef(), 1, null, false)); bindings.put("m3", v); }
            if(signature.contains("m4")) { Variable v = createLoadStore("m4", SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, null); v.setAllocation(new Registers.RegisterMainMem(v.getVariableRef(), 1, null, false)); bindings.put("m4", v); }
            if(signature.contains("m5")) { Variable v = createLoadStore("m5", SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, null); v.setAllocation(new Registers.RegisterMainMem(v.getVariableRef(), 1, null, false)); bindings.put("m5", v); }
            if(signature.contains("m6")) { Variable v = createLoadStore("m6", SymbolType.BYTE, scope, Variable.MemoryArea.MAIN_MEMORY, null); v.setAllocation(new Registers.RegisterMainMem(v.getVariableRef(), 1, null, false)); bindings.put("m6", v); }
         }
         if(signature.contains("c1")) bindings.put("c1", new ConstantInteger(310L));
         if(signature.contains("c2")) bindings.put("c2", new ConstantInteger(320L));
         if(signature.contains("c3")) bindings.put("c3", new ConstantInteger(330L));
         if(signature.contains("c4")) bindings.put("c4", new ConstantInteger(340L));
         if(signature.contains("c5")) bindings.put("c5", new ConstantInteger(350L));
         if(signature.contains("c6")) bindings.put("c6", new ConstantInteger(360L));
         if(signature.contains("la1")) bindings.put("la1", new Label("@1", scope, true));
         if(signature.startsWith("call_")) bindings.put("la1", new Label("@1", scope, true));
         if(signature.startsWith("call_")) bindings.put("c1", new ConstantInteger(400L));
         AsmFragmentInstance fragmentInstance =
               new AsmFragmentInstance(new Program(), signature, ScopeRef.ROOT, this, bindings);
         AsmProgram asm = new AsmProgram(targetCpu);
         asm.startChunk(ScopeRef.ROOT, null, signature);
         fragmentInstance.generate(asm, parsedBodyAsm);
         CpuClobber cpuClobber = asm.getClobber();
         this.clobber = new AsmFragmentClobber(cpuClobber);
         asm.addStash();
         this.cycles = asm.getCycles();
         this.bytes = asm.getBytes();
         this.bodyAsm = parsedBodyAsm;
      } finally {
         if(prevOverride != null) {
            Variable.setThreadAllocationOverride(prevOverride);
         }
      }
   }

   public String getSignature() {
      return signature;
   }

   public String getBody() {
      return body;
   }

   KickCParser.AsmLinesContext getBodyAsm() {
      if(bodyAsm == null) {
         initAsm();
      }
      return bodyAsm;
   }

   public TargetCpu getTargetCpu() {
      return targetCpu;
   }

   public AsmFragmentClobber getClobber() {
      if(clobber == null) {
         initAsm();
      }
      return clobber;
   }

   public double getCycles() {
      if(cycles == null) {
         initAsm();
      }
      return cycles;
   }

   public int getBytes() {
      if(bodyAsm == null || bytes < 0) {
         initAsm();
      }
      return bytes;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AsmFragmentTemplate that = (AsmFragmentTemplate) o;
      return Objects.equals(signature, that.signature) && targetCpu == that.targetCpu && Objects.equals(body, that.body);
   }

   @Override
   public int hashCode() {
      return Objects.hash(signature, targetCpu, body);
   }
}
