package dk.camelot64.kickc.model;

import dk.camelot64.kickc.CompileLog;
import dk.camelot64.kickc.OutputFileManager;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateMasterSynthesizer;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.symbols.*;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.ProcedureRef;
import dk.camelot64.kickc.model.values.ScopeRef;
import dk.camelot64.kickc.model.values.StringEncoding;
import dk.camelot64.kickc.passes.calcs.*;
import dk.camelot64.kickc.passes.utils.ProcedureUtils;
import dk.camelot64.kickc.passes.Pass2ConstantIdentification;
import dk.camelot64.kickc.passes.Pass4RegisterUpliftCombinations;
import dk.camelot64.kickc.passes.Pass5AsmOptimization;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/** A KickC Intermediate Compiler Language (ICL) Program */
public class Program {

   /** The log containing information about the compilation process. */
   private CompileLog log;

   /** The manager responsible for creating output files. PASS 0-5 (STATIC) */
   private OutputFileManager outputFileManager;

   /** Paths used for including files. PASS 0 (STATIC) */
   private List<String> includePaths;
   /** Paths used for library files. PASS 0 (STATIC) */
   private List<String> libraryPaths;
   /** All library C-files loaded. PASS 0-4 (STATIC) */
   private List<String> libraryFiles;
   /** Paths used for target files. PASS 0 (STATIC) */
   private List<String> targetPlatformPaths;
   /** All loaded H/C-files. PASS 0 (STATIC) */
   private List<String> loadedFiles;

   /** The target platform that the program is being build for. PASS 0-5 (STATIC) */
   private TargetPlatform targetPlatform;

   /** Base folder for finding ASM fragment files. (STATIC) */
   private Path asmFragmentBaseFolder;
   /** The ASM fragment synthesizer responsible for loading/synthesizing ASM fragments. Depends on the target CPU. (STATIC) */
   private AsmFragmentTemplateMasterSynthesizer asmFragmentMasterSynthesizer;

   /** Missing fragments produce a warning instead of an error (STATIC) */
   private boolean warnFragmentMissing = false;
   /** Array syntax used on types (eg. char[8] x; ) produce a warning instead of an error (STATIC) */
   private boolean warnArrayType = false;
   /** Enable live ranges per call path optimization, which limits memory usage and code, but takes a lot of compile time. */
   private boolean enableLiveRangeCallPath = true;

   /** Reserved ZP addresses that the compiler cannot use. PASS 0-5 (STATIC) */
   private List<Integer> reservedZps;
   /** Zero-waste loop alignment threshold in bytes. PASS 0-5 (STATIC) */
   private int loopAlignThreshold = 0;
   /** Segment type metadata configured via #pragma segment_type. PASS 0-5 (STATIC) */
   private final Map<String, SegmentTypeInfo> segmentTypes = new LinkedHashMap<>();
   /** Resource files that should be copied to the output folder to be compiled with the generated ASM. PASS 0-5 (STATIC) */
   private final List<Path> asmResourceFiles;
   /** Comments for the (main) file. PASS 0-4 (STATIC) */
   private List<Comment> mainFileComments;

   /** The main scope. PASS 0-5 (DYNAMIC) */
   private ProgramScope scope;
   /** The procedure that starts the execution of the program. (Default: _start() which calls _init() and main() ) */
   private ProcedureRef startProcedure;

   /** Struct values unwound to individual variables. PASS 1 (STATIC) */
   private StructVariableMemberUnwinding structVariableMemberUnwinding;

   /** Cached information about calls. PASS 1-4 (CACHED ON-DEMAND) */
   private volatile CallGraph callGraph;

   /** The procedures being compiled. PASS 1-5 (DYNAMIC)*/
   private List<ProcedureCompilation> procedureCompilations;
   /** O(1) lookup index for procedure compilations by reference. */
   private volatile Map<ProcedureRef, ProcedureCompilation> procedureCompilationMap;
   /** Cached set of procedures whose address is taken. */
   private volatile Set<ProcedureRef> addressOfProceduresCache;

   /** Variables modified inside procedures. PASS 1 (STATIC) */
   private ProcedureModifiedVars procedureModifiedVars;

   /** Registers potentially usable as allocation for each live range equivalence class. PASS 4 (DYNAMIC) */
   private RegisterPotentials registerPotentials;
   /** Live range equivalence classes containing variables that do not have overlapping live ranges. PASS 3-5 (DYNAMIC) */
   private LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet;
   /** The 6502 ASM program. PASS 4-5 (DYNAMIC) */
   private AsmProgram asm;

   /** Cached information about the variables referenced by blocks/statements. PASS 1-4 (CACHED ON-DEMAND) */
   private volatile VariableReferenceInfos variableReferenceInfos;
   /** Cached information about the closure of all block successors. */
   private volatile ControlFlowBlockSuccessorClosure blockSuccessorClosure;
   /** Information about dominators of all blocks. PASS 2U-4 (CACHED ON-DEMAND) */
   private volatile DominatorsGraph dominators;
   /** Cached information about symbols. Contains a symbol table cache for fast access. PASS 3-4 (CACHED ON-DEMAND) */
   private volatile SymbolInfos symbolInfos;
   /** Cached phi transitions into each block. PASS 4 (CACHED ON-DEMAND) */
   private volatile Map<LabelRef, PhiTransitions> phiTransitions;
   /** The live ranges of all variables. PASS 3-4 (CACHED ON-DEMAND) */
   private volatile LiveRangeVariables liveRangeVariables;
   /** The effective live ranges of all variables. PASS 3-4 (CACHED ON-DEMAND) */
   private volatile LiveRangeVariablesEffective liveRangeVariablesEffective;
   /** Separation of live range equivalence classes into scopes - used for register uplift. PASS 4 (CACHED ON-DEMAND) */
   private volatile RegisterUpliftProgram registerUpliftProgram;
   /** Cached information about which block is each statement a part of. PASS 2U-5 (CACHED ON-DEMAND) */
   private volatile StatementInfos statementInfos;
   /** Information about loops. PASS 2U-5 (CACHED ON-DEMAND) */
   private volatile NaturalLoopSet loopSet;
   /** The register weight of all variables describing how much the variable would theoretically gain from being in a register. PASS 3-5 (CACHED ON-DEMAND) */
   private volatile VariableRegisterWeights variableRegisterWeights;
   /** Cached live range interference matrix. PASS 4 (CACHED ON-DEMAND) */
   private volatile LiveRangeInterference liveRangeInterference;

   /** Per-program cache for Pass 4 register uplift analysis. */
   private volatile Pass4RegisterUpliftCombinations.UpliftProgramCache upliftProgramCache;

   /** Cached O(1) variable lookup index for Pass 5 ASM optimizations. */
   private volatile Pass5AsmOptimization.AsmVarLookupIndex asmVarLookupIndex;

   /** Custom string encodings registered for this program (via #pragma charmap). */
   private final Map<String, StringEncoding> customStringEncodings = new LinkedHashMap<>();
   /** Custom string encoding suffixes registered for this program. */
   private final Map<String, StringEncoding> customStringEncodingSuffixes = new LinkedHashMap<>();

   public Program() {
      this.outputFileManager = new OutputFileManager();
      this.scope = new ProgramScope();
      this.log = new CompileLog();
      this.includePaths = new ArrayList<>();
      this.libraryPaths = new ArrayList<>();
      this.libraryFiles = new ArrayList<>();
      this.targetPlatformPaths = new ArrayList<>();
      this.loadedFiles = new ArrayList<>();
      this.asmResourceFiles = new ArrayList<>();
      this.reservedZps = new ArrayList<>();
      this.procedureCompilations = new ArrayList<>();
      this.procedureCompilationMap = new HashMap<>();
   }

   /**
    * Clears all data that is only used in PASS 1
    */
   public void endPass1() {
      this.includePaths = null;
      this.targetPlatformPaths = null;
      this.loadedFiles = null;
      this.structVariableMemberUnwinding = null;
      clearAddressOfProceduresCache();
   }

   /**
    * Clears all data that is only used in PASS 2-4
    */
   public void endPass4() {
      this.procedureModifiedVars = null;
      this.libraryPaths = null;
      this.libraryFiles = null;
      this.mainFileComments = null;
      this.callGraph = null;
      this.variableReferenceInfos = null;
      this.dominators = null;
      this.symbolInfos = null;
      this.phiTransitions = null;
      this.liveRangeVariables = null;
      this.liveRangeVariablesEffective = null;
      this.registerPotentials = null;
      this.registerUpliftProgram = null;
      this.asmVarLookupIndex = null;
      clearAddressOfProceduresCache();
   }

   private final Graph totalGraph = new Graph() {
      @Override
      public Block getBlock(LabelRef symbol) {
         if(symbol == null) {
            return null;
         }
         Map<ProcedureRef, ProcedureCompilation> procMap = procedureCompilationMap;
         ProcedureCompilation ownerPc = procMap.get(new ProcedureRef(symbol.getFullName()));
         if(ownerPc != null) {
            ControlFlowGraph g = ownerPc.getGraph();
            if(g != null) {
               Block b = g.getBlock(symbol);
               if(b != null) return b;
            }
         }
         ProcedureCompilation scopePc = null;
         ProcedureCompilation rootPc = null;
         String scopeName = symbol.getScopeNames();
         if(!scopeName.isEmpty()) {
            scopePc = procMap.get(new ProcedureRef(scopeName));
            if(scopePc != null && scopePc != ownerPc) {
               ControlFlowGraph g = scopePc.getGraph();
               if(g != null) {
                  Block b = g.getBlock(symbol);
                  if(b != null) return b;
               }
            }
            int sep = scopeName.indexOf("::");
            if(sep > 0) {
               String rootProc = scopeName.substring(0, sep);
               rootPc = procMap.get(new ProcedureRef(rootProc));
               if(rootPc != null && rootPc != ownerPc && rootPc != scopePc) {
                  ControlFlowGraph g = rootPc.getGraph();
                  if(g != null) {
                     Block b = g.getBlock(symbol);
                     if(b != null) return b;
                  }
               }
            }
         }
         for(ProcedureCompilation pc : procedureCompilations) {
            if(pc == ownerPc || pc == scopePc || pc == rootPc) {
               continue;
            }
            ControlFlowGraph g = pc.getGraph();
            if(g != null) {
               Block b = g.getBlock(symbol);
               if(b != null) return b;
            }
         }
         return null;
      }

      @Override
      public List<Block> getAllBlocks() {
         List<Block> all = new ArrayList<>();
         for(ProcedureCompilation pc : procedureCompilations) {
            ControlFlowGraph g = pc.getGraph();
            if(g != null) {
               all.addAll(g.getAllBlocks());
            }
         }
         return Collections.unmodifiableList(all);
      }

      @Override
      public void addBlock(Block block) {
         throw new CompileError("Internal Error! Cannot add blocks to the read-only total control flow graph!");
      }
   };

   /**
    * Get a read-only control flow graph for the entire program.
    * @return The control flow graph
    */
   public Graph getGraph() {
      return totalGraph;
   }

   public List<ProcedureCompilation> getProcedureCompilations() {
      return procedureCompilations;
   }

   public void setProcedureCompilations(List<ProcedureCompilation> procedureCompilations) {
      this.procedureCompilations = procedureCompilations;
      Map<ProcedureRef, ProcedureCompilation> map = new HashMap<>(Math.max(16, procedureCompilations.size() * 2));
      for(ProcedureCompilation pc : procedureCompilations) {
         map.put(pc.getProcedureRef(), pc);
      }
      this.procedureCompilationMap = map;
   }

   /**
    * Pretty-print the entire control flow graph of all procedures.
    * @return The pretty-printed control flow graph
    */
   public String prettyControlFlowGraph() {
      StringBuilder graphPretty = new StringBuilder();
      for(ProcedureCompilation procedureCompilation : getProcedureCompilations()) {
         if(procedureCompilation.getGraph() == null) continue;
         graphPretty.append(procedureCompilation.getGraph().toString(this));
      }
      return graphPretty.toString();
   }

   public OutputFileManager getOutputFileManager() {
      return outputFileManager;
   }

   public void setEnableLiveRangeCallPath(boolean enableLiveRangeCallPath) {
      this.enableLiveRangeCallPath = enableLiveRangeCallPath;
   }

   public boolean isWarnFragmentMissing() {
      return warnFragmentMissing;
   }

   public void setWarnFragmentMissing(boolean warnFragmentMissing) {
      this.warnFragmentMissing = warnFragmentMissing;
   }

   public boolean isWarnArrayType() {
      return warnArrayType;
   }

   public void setWarnArrayType(boolean errorArrayKickC) {
      this.warnArrayType = errorArrayKickC;
   }

   public Path getAsmFragmentBaseFolder() {
      return asmFragmentBaseFolder;
   }

   public void setAsmFragmentBaseFolder(Path asmFragmentBaseFolder) {
      this.asmFragmentBaseFolder = asmFragmentBaseFolder;
   }

   public AsmFragmentTemplateMasterSynthesizer getAsmFragmentMasterSynthesizer() {
      return asmFragmentMasterSynthesizer;
   }

   public void initAsmFragmentMasterSynthesizer(Path asmFragmentCacheFolder) {
      this.asmFragmentMasterSynthesizer = new AsmFragmentTemplateMasterSynthesizer(asmFragmentCacheFolder, asmFragmentBaseFolder, getLog());
   }

   public TargetPlatform getTargetPlatform() {
      return targetPlatform;
   }

   public void setTargetPlatform(TargetPlatform targetPlatform) {
      this.targetPlatform = targetPlatform;
   }

   public TargetCpu getTargetCpu() {
      return getTargetPlatform().getCpu();
   }

   public StructVariableMemberUnwinding getStructVariableMemberUnwinding() {
      return structVariableMemberUnwinding;
   }

   public void setStructVariableMemberUnwinding(StructVariableMemberUnwinding structVariableMemberUnwinding) {
      this.structVariableMemberUnwinding = structVariableMemberUnwinding;
   }

   public List<Comment> getMainFileComments() {
      return mainFileComments;
   }

   public void setMainFileComments(List<Comment> mainFileComments) {
      this.mainFileComments = mainFileComments;
   }

   public List<String> getIncludePaths() {
      return includePaths;
   }

   public List<String> getLibraryPaths() {
      return libraryPaths;
   }

   public List<String> getLibraryFiles() {
      return libraryFiles;
   }

   public boolean isLibraryFile(String fileName) {
      if(fileName == null || libraryPaths == null) return false;
      try {
         Path path = Paths.get(fileName).toAbsolutePath().normalize();
         String normPath = path.toString().toLowerCase();
         for(String libPath : libraryPaths) {
            Path normLibPath = Paths.get(libPath).toAbsolutePath().normalize();
            String normLibStr = normLibPath.toString().toLowerCase();
            if(!normLibStr.endsWith(File.separator)) {
               normLibStr += File.separator;
            }
            if(normPath.startsWith(normLibStr)) {
               return true;
            }
         }
      } catch(Exception e) {
         // Ignore invalid paths
      }
      return false;
   }

   public List<String> getTargetPlatformPaths() {
      return targetPlatformPaths;
   }

   public List<String> getLoadedFiles() {
      return loadedFiles;
   }

   public List<Path> getAsmResourceFiles() {
      return asmResourceFiles;
   }

   public void addAsmResourceFile(Path asmResourceFile) {
      asmResourceFiles.add(asmResourceFile);
   }

   public ProcedureCompilation createProcedureCompilation(ProcedureRef procedureRef) {
      if(getProcedureCompilation(procedureRef)!=null)
         throw new CompileError("Error! Procedure already defined "+procedureRef.getFullName());
      final ProcedureCompilation procedureCompilation = new ProcedureCompilation(procedureRef);
      procedureCompilations.add(procedureCompilation);
      procedureCompilationMap.put(procedureRef, procedureCompilation);
      return procedureCompilation;
   }

   public ProcedureCompilation getProcedureCompilation(ProcedureRef procedureRef) {
      if(procedureRef == null) {
         return null;
      }
      return procedureCompilationMap.get(procedureRef);
   }

   public ProgramScope getScope() {
      return scope;
   }

   public void setScope(ProgramScope scope) {
      this.scope = scope;
      this.asmVarLookupIndex = null;
   }

   public Pass5AsmOptimization.AsmVarLookupIndex getAsmVarLookupIndex() {
      Pass5AsmOptimization.AsmVarLookupIndex idx = this.asmVarLookupIndex;
      if(idx == null && this.scope != null) {
         idx = Pass5AsmOptimization.AsmVarLookupIndex.build(this.scope);
         this.asmVarLookupIndex = idx;
      }
      return idx;
   }

   public void clearAsmVarLookupIndex() {
      this.asmVarLookupIndex = null;
   }

   public ProcedureRef getStartProcedure() {
      return startProcedure;
   }

   public void setStartProcedure(ProcedureRef startProcedure) {
      this.startProcedure = startProcedure;
   }

   public ProcedureModifiedVars getProcedureModifiedVars() {
      return procedureModifiedVars;
   }

   public void setProcedureModifiedVars(ProcedureModifiedVars procedureModifiedVars) {
      this.procedureModifiedVars = procedureModifiedVars;
   }

   public AsmProgram getAsm() {
      return asm;
   }

   public void setAsm(AsmProgram asm) {
      this.asm = asm;
   }

   /**
    * Get the call-graph for the program. Calculates the call-graph if it has not already been calculated.
    *
    * @return The call-graph
    */
   public CallGraph getCallGraph() {
      CallGraph result = callGraph;
      if(result == null) {
         synchronized(this) {
            result = callGraph;
            if(result == null) {
               result = new PassNCalcCallGraph(this).calculate();
               callGraph = result;
            }
         }
      }
      return result;
   }

   /**
    * Clears the call-graph ensuring it will be re-calculated if used.
    */
   public void clearCallGraph() {
      this.callGraph = null;
      clearAddressOfProceduresCache();
   }

   public VariableReferenceInfos getVariableReferenceInfos() {
      VariableReferenceInfos result = variableReferenceInfos;
      if(result == null) {
         synchronized(this) {
            result = variableReferenceInfos;
            if(result == null) {
               result = new PassNCalcVariableReferenceInfos(this).calculate();
               variableReferenceInfos = result;
            }
         }
      }
      return result;
   }

   public void clearVariableReferenceInfos() {
      this.variableReferenceInfos = null;
      clearAddressOfProceduresCache();
   }

   public Set<ProcedureRef> getAddressOfProceduresCache() {
      return addressOfProceduresCache;
   }

   public void setAddressOfProceduresCache(Set<ProcedureRef> addressOfProceduresCache) {
      this.addressOfProceduresCache = addressOfProceduresCache;
   }

   public void clearAddressOfProceduresCache() {
      this.addressOfProceduresCache = null;
   }

   public ControlFlowBlockSuccessorClosure getControlFlowBlockSuccessorClosure() {
      ControlFlowBlockSuccessorClosure result = blockSuccessorClosure;
      if(result == null) {
         synchronized(this) {
            result = blockSuccessorClosure;
            if(result == null) {
               result = new PassNCalcBlockSuccessorClosure(this).calculate();
               blockSuccessorClosure = result;
            }
         }
      }
      return result;
   }

   public void clearControlFlowBlockSuccessorClosure() {
      this.blockSuccessorClosure = null;
   }

   public DominatorsGraph getDominators() {
      DominatorsGraph result = dominators;
      if(result == null) {
         synchronized(this) {
            result = dominators;
            if(result == null) {
               result = new PassNCalcDominators(this).calculate();
               dominators = result;
            }
         }
      }
      return result;
   }

   public void clearDominators() {
      this.dominators = null;
   }

   public Map<LabelRef, PhiTransitions> getPhiTransitions() {
      Map<LabelRef, PhiTransitions> result = phiTransitions;
      if(result == null) {
         synchronized(this) {
            result = phiTransitions;
            if(result == null) {
               result = new PassNCalcPhiTransitions(this).calculate();
               phiTransitions = result;
            }
         }
      }
      return result;
   }

   public void clearPhiTransitions() {
      this.phiTransitions = null;
   }

   public LiveRangeVariables getLiveRangeVariables() {
      LiveRangeVariables result = liveRangeVariables;
      if(result == null) {
         synchronized(this) {
            result = liveRangeVariables;
            if(result == null) {
               result = new PassNCalcLiveRangeVariables(this).calculate();
               liveRangeVariables = result;
            }
         }
      }
      return result;
   }

   public void setLiveRangeVariables(LiveRangeVariables liveRangeVariables) {
      this.liveRangeVariables = liveRangeVariables;
   }

   public boolean hasLiveRangeVariables() {
      return this.liveRangeVariables != null;
   }

   public void clearLiveRangeVariables() {
      this.liveRangeVariables = null;
   }

   public StatementInfos getStatementInfos() {
      StatementInfos result = statementInfos;
      if(result == null) {
         synchronized(this) {
            result = statementInfos;
            if(result == null) {
               result = new PassNCalcStatementInfos(this).calculate();
               statementInfos = result;
            }
         }
      }
      return result;
   }

   public void clearStatementInfos() {
      this.statementInfos = null;
   }

   /**
    * Clear index numbers for all statements in the control flow graph.
    */
   public void clearStatementIndices() {
      getProcedureCompilations().stream().map(ProcedureCompilation::getGraph).filter(Objects::nonNull).forEach(ControlFlowGraph::clearStatementIndices);
   }

   public SymbolInfos getSymbolInfos() {
      SymbolInfos result = symbolInfos;
      if(result == null) {
         synchronized(this) {
            result = symbolInfos;
            if(result == null) {
               result = new PassNCalcSymbolInfos(this).calculate();
               symbolInfos = result;
            }
         }
      }
      return result;
   }

   public boolean hasLiveRangeVariablesEffective() {
      return liveRangeVariablesEffective != null;
   }

   public LiveRangeVariablesEffective getLiveRangeVariablesEffective() {
      LiveRangeVariablesEffective result = liveRangeVariablesEffective;
      if(result == null) {
         synchronized(this) {
            result = liveRangeVariablesEffective;
            if(result == null) {
               if(enableLiveRangeCallPath) {
                  result = new PassNCalcLiveRangesEffectiveCallPaths(this).calculate();
               } else {
                  result = new PassNCalcLiveRangesEffectiveSimple(this).calculate();
               }
               liveRangeVariablesEffective = result;
            }
         }
      }
      return result;
   }

   public void clearLiveRangeVariablesEffective() {
      this.liveRangeVariablesEffective = null;
   }

   public RegisterUpliftProgram getRegisterUpliftProgram() {
      RegisterUpliftProgram result = registerUpliftProgram;
      if(result == null) {
         synchronized(this) {
            result = registerUpliftProgram;
            if(result == null) {
               result = new PassNCalcRegisterUpliftProgram(this).calculate();
               registerUpliftProgram = result;
            }
         }
      }
      return result;
   }

   public NaturalLoopSet getLoopSet() {
      NaturalLoopSet result = loopSet;
      if(result == null) {
         synchronized(this) {
            result = loopSet;
            if(result == null) {
               result = new PassNCalcLoopSet(this).calculate();
               loopSet = result;
            }
         }
      }
      return result;
   }

   public void clearLoopSet() {
      this.loopSet = null;
   }

   public VariableRegisterWeights getVariableRegisterWeights() {
      VariableRegisterWeights result = variableRegisterWeights;
      if(result == null) {
         synchronized(this) {
            result = variableRegisterWeights;
            if(result == null) {
               result = new PassNCalcVariableRegisterWeight(this).calculate();
               variableRegisterWeights = result;
            }
         }
      }
      return result;
   }

   public VariableRegisterWeights getOrNullVariableRegisterWeights() {
      return variableRegisterWeights;
   }

   public LiveRangeEquivalenceClassSet getLiveRangeEquivalenceClassSet() {
      return liveRangeEquivalenceClassSet;
   }

   public void setLiveRangeEquivalenceClassSet(LiveRangeEquivalenceClassSet liveRangeEquivalenceClassSet) {
      this.liveRangeEquivalenceClassSet = liveRangeEquivalenceClassSet;
   }

   public RegisterPotentials getRegisterPotentials() {
      return registerPotentials;
   }

   public void setRegisterPotentials(RegisterPotentials registerPotentials) {
      this.registerPotentials = registerPotentials;
   }

   public LiveRangeInterference getLiveRangeInterference() {
      LiveRangeInterference result = liveRangeInterference;
      if(result == null && liveRangeEquivalenceClassSet != null) {
         synchronized(this) {
            result = liveRangeInterference;
            if(result == null && liveRangeEquivalenceClassSet != null) {
               result = new LiveRangeInterference(this);
               liveRangeInterference = result;
            }
         }
      }
      return result;
   }

   public void setLiveRangeInterference(LiveRangeInterference liveRangeInterference) {
      this.liveRangeInterference = liveRangeInterference;
   }

   public void clearLiveRangeInterference() {
      this.liveRangeInterference = null;
   }

   public Pass4RegisterUpliftCombinations.UpliftProgramCache getUpliftProgramCache() {
      Pass4RegisterUpliftCombinations.UpliftProgramCache result = upliftProgramCache;
      if(result == null) {
         synchronized(this) {
            result = upliftProgramCache;
            if(result == null) {
               result = new Pass4RegisterUpliftCombinations.UpliftProgramCache();
               upliftProgramCache = result;
            }
         }
      }
      return result;
   }

   public void registerCustomStringEncoding(StringEncoding encoding) {
      customStringEncodings.put(encoding.name.toLowerCase(Locale.ENGLISH), encoding);
      if(encoding.suffix != null && !encoding.suffix.isEmpty()) {
         customStringEncodingSuffixes.put(encoding.suffix.toLowerCase(Locale.ENGLISH), encoding);
      }
   }

   public StringEncoding getCustomStringEncoding(String nameLower) {
      return customStringEncodings.get(nameLower);
   }

   public StringEncoding getCustomStringEncodingBySuffix(String suffixLower) {
      return customStringEncodingSuffixes.get(suffixLower);
   }

   /**
    * Adds a bunch of reserved zero-page addresses that the compiler is not allowed to use.
    *
    * @param reservedZp addresses to reserve
    */
   public void addReservedZps(List<Integer> reservedZp) {
      for(Integer zp : reservedZp) {
         if(!this.reservedZps.contains(zp)) {
            this.reservedZps.add(zp);
         }
      }
   }

   public void addReservedZp(int zp) {
      if(!this.reservedZps.contains(zp)) {
         this.reservedZps.add(zp);
      }
   }

   public List<Integer> getReservedZps() {
      return reservedZps;
   }

   public void setReservedZps(List<Integer> reservedZps) {
      this.reservedZps = reservedZps;
   }

   public void setSegmentType(String segmentName, SegmentTypeInfo info) {
      this.segmentTypes.put(segmentName, info);
   }

   public SegmentTypeInfo getSegmentType(String segmentName) {
      return this.segmentTypes.get(segmentName);
   }

   public boolean isSegmentRom(String segmentName) {
      if(segmentName == null) return false;
      SegmentTypeInfo info = this.segmentTypes.get(segmentName);
      return info != null && info.isRom();
   }

   public CompileLog getLog() {
      return log;
   }

   public void setLog(CompileLog log) {
      this.log = log;
   }

   /**
    * Get information about the size of the program
    *
    * @return Size information
    */
   public String getSizeInfo() {
      StringBuilder sizeInfo = new StringBuilder();
      sizeInfo.append(getScope().getSizeInfo());

      final List<Graph.Block> allBlocks = getProcedureCompilations().stream().map(ProcedureCompilation::getGraph).filter(Objects::nonNull).map(ControlFlowGraph::getAllBlocks).flatMap(Collection::stream).toList();
      sizeInfo.append("SIZE blocks ").append(allBlocks.size()).append("\n");
      int numStmt = allBlocks.stream().mapToInt(block -> block.getStatements().size()).sum();
      sizeInfo.append("SIZE statements ").append(numStmt).append("\n");
      int numPhiVars = allBlocks.stream().mapToInt(value -> value.getStatements().stream().mapToInt(value1 -> (value1 instanceof StatementPhiBlock) ? ((StatementPhiBlock) value1).getPhiVariables().size() : 0).sum()).sum();
      sizeInfo.append("SIZE phi variables ").append(numPhiVars).append("\n");

      if(variableReferenceInfos != null)
         sizeInfo.append(variableReferenceInfos.getSizeInfo());
      if(getLiveRangeEquivalenceClassSet() != null)
         sizeInfo.append(getLiveRangeEquivalenceClassSet().getSizeInfo());
      if(liveRangeVariablesEffective != null)
         sizeInfo.append(liveRangeVariablesEffective.getSizeInfo());
      if(getAsm() != null)
         sizeInfo.append(getAsm().getSizeInfo());
      return sizeInfo.toString();
   }

   /**
    * Get the procedure, that the block is part of. Null if the block is not part of a procedure.
    *
    * @param block The control flow graph block
    * @return the procedure, that the block is part of
    */
   public Procedure getProcedure(Graph.Block block) {
      final ScopeRef scopeRef = block.getScope();
      final Scope scope = getScope().getScope(scopeRef);
      if(scope instanceof Procedure) {
         return (Procedure) scope;
      } else {
         return null;
      }
   }

   /**
    * Delete an entire procedure.
    * Removes the entire control flow graph and all symbols.
    * Does not check that the removal results in a legal program.
    * @param procedureRef The procedure to remove.
    */
   public void removeProcedure(ProcedureRef procedureRef) {
      Procedure procedure = getScope().getProcedure(procedureRef);
      procedure.getScope().remove(procedure);
      ProcedureCompilation removed = procedureCompilationMap.remove(procedureRef);
      if(removed != null) {
         procedureCompilations.remove(removed);
      }
   }

   /**
    * Is the block the entry of a procedure, ie. the first block of the code of the procedure.
    *
    * @param block
    * @return true if this is the entry of a procedure
    */
   public boolean isProcedureEntry(Graph.Block block) {
      Symbol symbol = getScope().getSymbol(block.getLabel());
      return (symbol instanceof Procedure);
   }

   /**
    * Get all blocks that are program entry points.
    * This is the start-block and any blocks referenced by the address-off operator (&)
    */
   public List<Graph.Block> getEntryPointBlocks() {
      final Graph graph = getGraph();
      List<Graph.Block> entryPointBlocks = new ArrayList<>();
      Set<ProcedureRef> addressOfProcedures = Pass2ConstantIdentification.getAllAddressOfProcedures(this);
      for(Procedure procedure : getScope().getAllProcedures(true)) {
         if(ProcedureUtils.isEntrypoint(procedure.getRef(), this, addressOfProcedures) || Procedure.CallingConvention.STACK_CALL.equals(procedure.getCallingConvention()) || Procedure.CallingConvention.VAR_CALL.equals(procedure.getCallingConvention())) {
            // Address-of is used on the procedure
            Label procedureLabel = procedure.getLabel();
            Graph.Block procedureBlock = graph.getBlock(procedureLabel.getRef());
            entryPointBlocks.add(procedureBlock);
         }
      }
      return entryPointBlocks;
   }


   public int getLoopAlignThreshold() {
      return loopAlignThreshold;
   }

   public void setLoopAlignThreshold(int loopAlignThreshold) {
      this.loopAlignThreshold = loopAlignThreshold;
   }

   private boolean enableConvenience = false;

   public boolean isEnableConvenience() {
      return enableConvenience;
   }

   public void setEnableConvenience(boolean enableConvenience) {
      this.enableConvenience = enableConvenience;
   }

   private List<StaticAssert> staticAsserts = new ArrayList<>();

   public List<StaticAssert> getStaticAsserts() {
      return staticAsserts;
   }

   public void addStaticAssert(StaticAssert staticAssert) {
      this.staticAsserts.add(staticAssert);
   }
}
