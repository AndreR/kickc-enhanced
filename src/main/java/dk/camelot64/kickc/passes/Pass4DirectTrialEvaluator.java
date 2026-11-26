package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.asm.AsmProgram;
import dk.camelot64.kickc.fragment.*;
import dk.camelot64.kickc.fragment.signature.AsmFragmentBindings;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateSynthesizer;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.InternalError;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates register allocation trials directly from fragment template metadata
 * (clobber, cycles, bytes) without constructing {@link AsmProgram} or running ANTLR visitors.
 */
public final class Pass4DirectTrialEvaluator {

   public record TrialEvalResult(boolean success, int score, int bytes) {
   }

   private static final int MASK_A = 1;
   private static final int MASK_X = 2;
   private static final int MASK_Y = 4;
   private static final int MASK_Z = 8;
   private static final int MASK_ALL = MASK_A | MASK_X | MASK_Y | MASK_Z;

   private static final TrialEvalResult FAILED_RESULT = new TrialEvalResult(false, Integer.MAX_VALUE, Integer.MAX_VALUE);

   private static final CpuClobber[] CLOBBER_CACHE = new CpuClobber[16];
   static {
      CLOBBER_CACHE[0] = CpuClobber.CLOBBER_NONE;
      for(int i = 1; i < 15; i++) {
         CLOBBER_CACHE[i] = new CpuClobber(
               (i & MASK_A) != 0,
               (i & MASK_X) != 0,
               (i & MASK_Y) != 0,
               (i & MASK_Z) != 0,
               false, false, false, false, false, false, false, false, false, false
         );
      }
      CLOBBER_CACHE[15] = CpuClobber.CLOBBER_ALL;
   }

   private static final VarHandle FRAGMENT_TEMPLATE_HANDLE;
   static {
      try {
         MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(AsmFragmentInstance.class, MethodHandles.lookup());
         FRAGMENT_TEMPLATE_HANDLE = lookup.findVarHandle(AsmFragmentInstance.class, "fragmentTemplate", AsmFragmentTemplate.class);
      } catch(ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   private Pass4DirectTrialEvaluator() {
   }

   private static final class EvalAcc {
      int clobberMask;
      double cycles;
      int bytes;
      int blockScore;
      int blockBytes;
      int totalScore;
      int totalBytes;

      int addChunkCost(int statementIdx, double[] stmtMultipliers) {
         int weighted = 0;
         if(cycles > 0) {
            double mult = (stmtMultipliers != null && statementIdx >= 0 && statementIdx < stmtMultipliers.length)
                  ? stmtMultipliers[statementIdx]
                  : 10.0;
            weighted = (int) (cycles * mult);
            blockScore += weighted;
            totalScore += weighted;
         }
         blockBytes += bytes;
         totalBytes += bytes;
         return weighted;
      }
   }

   /**
    * Precomputed zero-HashMap clobber index mapping variables to dense integer IDs
    * and per-statement / per-PHI-assignment alive variable ID arrays.
    */
   static final class FastClobberIndex {
      private static final int[] EMPTY_INT_ARRAY = new int[0];
      private static final int[][] EMPTY_INT_2D_ARRAY = new int[0][];
      private static final PhiTransitionMeta[] EMPTY_PHI_META_ARRAY = new PhiTransitionMeta[0];

      static final class LocalState {
         long version;
         final byte[] regMasks;
         final Registers.Register[] allocations;
         final boolean[] overridden;

         LocalState(long version, byte[] regMasks, Registers.Register[] allocations, int numVars) {
            this.version = version;
            this.regMasks = regMasks;
            this.allocations = allocations;
            this.overridden = new boolean[numVars];
         }
      }

      static final class PhiTransitionMeta {
         final int[][] clobberVarIds;
         final int[] lVarIds;
         final String[] lTypePrefixes;
         final int[] rVarIds;
         final String[] rTypePrefixes;
         final int[] referencedVarIds;
         final int[] baseAssignClobberMasks;
         int baseClobberMask;
         int baseWeightedScore;
         int baseBytes;
         volatile boolean baseValid;

         PhiTransitionMeta(
               int[][] clobberVarIds,
               int[] lVarIds,
               String[] lTypePrefixes,
               int[] rVarIds,
               String[] rTypePrefixes,
               int[] referencedVarIds) {
            this.clobberVarIds = clobberVarIds;
            this.lVarIds = lVarIds;
            this.lTypePrefixes = lTypePrefixes;
            this.rVarIds = rVarIds;
            this.rTypePrefixes = rTypePrefixes;
            this.referencedVarIds = referencedVarIds;
            this.baseAssignClobberMasks = lVarIds.length == 0 ? EMPTY_INT_ARRAY : new int[lVarIds.length];
         }
      }

      final Map<LabelRef, PhiTransitions> phiTransitionsRef;
      final int numVars;
      final int numStmts;
      final Map<VariableRef, Integer> varIdMap;
      final Variable[] varsById;
      final byte[] baseCpuRegMask;
      final Registers.Register[] baseAllocations;
      final int[][] stmtAliveNonAssignedVarIds;
      final int[][] stmtReferencedVarIds;
      final int[][] varToStmtIds;
      final PhiTransitionMeta[][] varToPhiMetas;
      final int[] baseStmtClobberMask;
      final int[] baseStmtWeightedScore;
      final int[] baseStmtBytes;
      final boolean[] baseStmtValid;
      final IdentityHashMap<PhiTransitions.PhiTransition, PhiTransitionMeta> phiTransitionMetas;
      volatile long version = 1L;
      private final ThreadLocal<LocalState> threadLocalState = new ThreadLocal<>();

      FastClobberIndex(Program program, Map<LabelRef, PhiTransitions> phiTransitions) {
         this.phiTransitionsRef = phiTransitions;
         SymbolInfos symbolInfos = program.getSymbolInfos();
         LiveRangeVariablesEffective liveRangeVars = program.getLiveRangeVariablesEffective();
         VariableReferenceInfos varRefInfos = program.getVariableReferenceInfos();
         Collection<Variable> allVars = program.getScope().getAllVariables(true);

         List<Variable> varList = new ArrayList<>(allVars.size());
         Map<VariableRef, Integer> idMap = HashMap.newHashMap(allVars.size());
         for(Variable v : allVars) {
            VariableRef ref = v.getVariableRef();
            if(!idMap.containsKey(ref)) {
               idMap.put(ref, varList.size());
               varList.add(v);
            }
         }

         Collection<Statement> allStatements = program.getGraph().getAllStatements();
         int maxStmtIdx = -1;
         for(Statement stmt : allStatements) {
            Integer idx = stmt.getIndex();
            if(idx != null && idx > maxStmtIdx) {
               maxStmtIdx = idx;
            }
         }
         this.numStmts = maxStmtIdx >= 0 ? maxStmtIdx + 1 : 0;
         this.stmtAliveNonAssignedVarIds = new int[this.numStmts][];
         this.stmtReferencedVarIds = new int[this.numStmts][];

         for(Statement stmt : allStatements) {
            if(stmt instanceof StatementPhiBlock) {
               continue;
            }
            Integer idx = stmt.getIndex();
            if(idx == null || idx < 0) {
               continue;
            }
            Collection<VariableRef> aliveVars = liveRangeVars.getAliveEffective(stmt);
            if(aliveVars.isEmpty()) {
               this.stmtAliveNonAssignedVarIds[idx] = EMPTY_INT_ARRAY;
            } else {
               int[] tmp = new int[aliveVars.size()];
               int count = 0;
               for(VariableRef aliveVar : aliveVars) {
                  if(!isStatementAssignedVar(stmt, aliveVar)) {
                     Integer varId = resolveVarId(aliveVar, idMap, varList, symbolInfos);
                     if(varId != null) {
                        tmp[count++] = varId;
                     }
                  }
               }
               if(count == 0) {
                  this.stmtAliveNonAssignedVarIds[idx] = EMPTY_INT_ARRAY;
               } else if(count == tmp.length) {
                  this.stmtAliveNonAssignedVarIds[idx] = tmp;
               } else {
                  this.stmtAliveNonAssignedVarIds[idx] = Arrays.copyOf(tmp, count);
               }
            }

            Collection<VariableRef> refVars = varRefInfos.getReferencedVars(stmt);
            if(refVars.isEmpty()) {
               this.stmtReferencedVarIds[idx] = EMPTY_INT_ARRAY;
            } else {
               int[] tmpRef = new int[refVars.size()];
               int refCount = 0;
               for(VariableRef refVar : refVars) {
                  Integer varId = resolveVarId(refVar, idMap, varList, symbolInfos);
                  if(varId != null) {
                     int idVal = varId;
                     boolean dup = false;
                     for(int k = 0; k < refCount; k++) {
                        if(tmpRef[k] == idVal) {
                           dup = true;
                           break;
                        }
                     }
                     if(!dup) {
                        tmpRef[refCount++] = idVal;
                     }
                  }
               }
               if(refCount == 0) {
                  this.stmtReferencedVarIds[idx] = EMPTY_INT_ARRAY;
               } else if(refCount == tmpRef.length) {
                  this.stmtReferencedVarIds[idx] = tmpRef;
               } else {
                  this.stmtReferencedVarIds[idx] = Arrays.copyOf(tmpRef, refCount);
               }
            }
         }

         this.phiTransitionMetas = new IdentityHashMap<>();
         for(PhiTransitions pts : phiTransitions.values()) {
            for(Graph.Block fromBlock : pts.getFromBlocks()) {
               PhiTransitions.PhiTransition transition = pts.getTransition(fromBlock);
               if(this.phiTransitionMetas.containsKey(transition)) {
                  continue;
               }
               List<PhiTransitions.PhiTransition.PhiAssignment> assignments = transition.getAssignments();
               if(assignments.isEmpty()) {
                  this.phiTransitionMetas.put(transition, new PhiTransitionMeta(EMPTY_INT_2D_ARRAY, EMPTY_INT_ARRAY, new String[0], EMPTY_INT_ARRAY, new String[0], EMPTY_INT_ARRAY));
                  continue;
               }
               Statement phiBlock = assignments.get(0).getPhiBlock();
               Collection<VariableRef> baseAlive = liveRangeVars.getAliveEffective(phiBlock);
               List<StatementPhiBlock.PhiVariable> phiVars = (phiBlock instanceof StatementPhiBlock pb) ? pb.getPhiVariables() : null;
               Set<VariableRef> allPhiAssigned = null;
               if(phiVars != null && phiVars.size() > assignments.size()) {
                  allPhiAssigned = Pass4RegisterUpliftPotentialRegisterAnalysis.getAssignedVars(phiBlock);
               }

               int n = assignments.size();
               int[][] perAssignment = new int[n][];
               int[] lVarIds = new int[n];
               String[] lTypePrefixes = new String[n];
               int[] rVarIds = new int[n];
               String[] rTypePrefixes = new String[n];
               Set<Integer> transitionRefIds = new LinkedHashSet<>();

               for(int i = 0; i < n; i++) {
                  PhiTransitions.PhiTransition.PhiAssignment current = assignments.get(i);
                  int currentIdx = current.getAssignmentIdx();
                  VariableRef currentVar = current.getVariable();

                  Integer lId = resolveVarId(currentVar, idMap, varList, symbolInfos);
                  lVarIds[currentIdx] = (lId != null) ? lId : -1;
                  if(lId != null) {
                     transitionRefIds.add(lId);
                     try {
                        lTypePrefixes[currentIdx] = AsmFragmentBindings.getTypePrefix(varList.get(lId).getType());
                     } catch(RuntimeException ignored) {
                        lTypePrefixes[currentIdx] = null;
                     }
                  }

                  RValue rVal = current.getrValue();
                  if(rVal instanceof VariableRef rVarRef) {
                     Integer rId = resolveVarId(rVarRef, idMap, varList, symbolInfos);
                     rVarIds[currentIdx] = (rId != null) ? rId : -1;
                     if(rId != null) {
                        transitionRefIds.add(rId);
                        try {
                           rTypePrefixes[currentIdx] = AsmFragmentBindings.getTypePrefix(varList.get(rId).getType());
                        } catch(RuntimeException ignored) {
                           rTypePrefixes[currentIdx] = null;
                        }
                     }
                  } else if(rVal instanceof ConstantInteger || rVal instanceof ConstantBool || rVal instanceof ConstantChar) {
                     rVarIds[currentIdx] = -1;
                     try {
                        SymbolType cType = SymbolTypeInference.inferType(program.getScope(), (ConstantValue) rVal);
                        rTypePrefixes[currentIdx] = AsmFragmentBindings.getTypePrefix(cType);
                     } catch(RuntimeException ignored) {
                        rTypePrefixes[currentIdx] = null;
                     }
                  } else {
                     rVarIds[currentIdx] = -1;
                     rTypePrefixes[currentIdx] = null;
                     for(VariableRef rRefVar : VariableReferenceInfos.getReferencedVars(rVal)) {
                        Integer rRefId = resolveVarId(rRefVar, idMap, varList, symbolInfos);
                        if(rRefId != null) {
                           transitionRefIds.add(rRefId);
                        }
                     }
                  }

                  Set<VariableRef> extraAssigned = null;
                  if(allPhiAssigned != null) {
                     extraAssigned = new HashSet<>(allPhiAssigned);
                     for(int j = 0; j < n; j++) {
                        PhiTransitions.PhiTransition.PhiAssignment other = assignments.get(j);
                        if(other.getAssignmentIdx() != currentIdx) {
                           extraAssigned.remove(other.getVariable());
                        }
                     }
                  }

                  Set<Integer> ids = new LinkedHashSet<>();
                  for(VariableRef aliveVar : baseAlive) {
                     if(extraAssigned != null ? !extraAssigned.contains(aliveVar) : !aliveVar.equals(currentVar)) {
                        Integer varId = resolveVarId(aliveVar, idMap, varList, symbolInfos);
                        if(varId != null) {
                           ids.add(varId);
                        }
                     }
                  }
                  for(int j = 0; j < n; j++) {
                     PhiTransitions.PhiTransition.PhiAssignment other = assignments.get(j);
                     if(other.getAssignmentIdx() > currentIdx) {
                        Collection<VariableRef> laterRefs = VariableReferenceInfos.getReferencedVars(other.getrValue());
                        for(VariableRef aliveVar : laterRefs) {
                           if(extraAssigned != null ? !extraAssigned.contains(aliveVar) : !aliveVar.equals(currentVar)) {
                              Integer varId = resolveVarId(aliveVar, idMap, varList, symbolInfos);
                              if(varId != null) {
                                 ids.add(varId);
                              }
                           }
                        }
                     }
                  }
                  if(ids.isEmpty()) {
                     perAssignment[currentIdx] = EMPTY_INT_ARRAY;
                  } else {
                     int[] arr = new int[ids.size()];
                     int k = 0;
                     for(Integer varId : ids) {
                        arr[k++] = varId;
                     }
                     perAssignment[currentIdx] = arr;
                  }
               }
               int[] referencedVarIds;
               if(transitionRefIds.isEmpty()) {
                  referencedVarIds = EMPTY_INT_ARRAY;
               } else {
                  referencedVarIds = new int[transitionRefIds.size()];
                  int rk = 0;
                  for(Integer rId : transitionRefIds) {
                     referencedVarIds[rk++] = rId;
                  }
               }
               this.phiTransitionMetas.put(transition, new PhiTransitionMeta(perAssignment, lVarIds, lTypePrefixes, rVarIds, rTypePrefixes, referencedVarIds));
            }
         }

         this.varIdMap = idMap;
         this.numVars = varList.size();
         this.varsById = varList.toArray(new Variable[0]);
         this.baseCpuRegMask = new byte[this.numVars];
         this.baseAllocations = new Registers.Register[this.numVars];
         for(int i = 0; i < this.numVars; i++) {
            Registers.Register alloc = this.varsById[i].getAllocation();
            this.baseAllocations[i] = alloc;
            this.baseCpuRegMask[i] = toRegMask(alloc);
         }

         int[] varStmtCounts = new int[this.numVars];
         for(int stmtIdx = 0; stmtIdx < this.numStmts; stmtIdx++) {
            int[] refs = this.stmtReferencedVarIds[stmtIdx];
            if(refs != null) {
               for(int j = 0; j < refs.length; j++) {
                  varStmtCounts[refs[j]]++;
               }
            }
         }
         this.varToStmtIds = new int[this.numVars][];
         for(int v = 0; v < this.numVars; v++) {
            int c = varStmtCounts[v];
            this.varToStmtIds[v] = (c == 0) ? EMPTY_INT_ARRAY : new int[c];
         }
         int[] varStmtPos = new int[this.numVars];
         for(int stmtIdx = 0; stmtIdx < this.numStmts; stmtIdx++) {
            int[] refs = this.stmtReferencedVarIds[stmtIdx];
            if(refs != null) {
               for(int j = 0; j < refs.length; j++) {
                  int v = refs[j];
                  this.varToStmtIds[v][varStmtPos[v]++] = stmtIdx;
               }
            }
         }

         int[] varPhiCounts = new int[this.numVars];
         for(PhiTransitionMeta meta : this.phiTransitionMetas.values()) {
            int[] refs = meta.referencedVarIds;
            for(int j = 0; j < refs.length; j++) {
               varPhiCounts[refs[j]]++;
            }
         }
         this.varToPhiMetas = new PhiTransitionMeta[this.numVars][];
         for(int v = 0; v < this.numVars; v++) {
            int c = varPhiCounts[v];
            this.varToPhiMetas[v] = (c == 0) ? EMPTY_PHI_META_ARRAY : new PhiTransitionMeta[c];
         }
         int[] varPhiPos = new int[this.numVars];
         for(PhiTransitionMeta meta : this.phiTransitionMetas.values()) {
            int[] refs = meta.referencedVarIds;
            for(int j = 0; j < refs.length; j++) {
               int v = refs[j];
               this.varToPhiMetas[v][varPhiPos[v]++] = meta;
            }
         }

         this.baseStmtClobberMask = new int[this.numStmts];
         this.baseStmtWeightedScore = new int[this.numStmts];
         this.baseStmtBytes = new int[this.numStmts];
         this.baseStmtValid = new boolean[this.numStmts];
      }

      private static Integer resolveVarId(
            VariableRef ref,
            Map<VariableRef, Integer> idMap,
            List<Variable> varList,
            SymbolInfos symbolInfos) {
         Integer id = idMap.get(ref);
         if(id != null) {
            return id;
         }
         Variable var = symbolInfos.getVariable(ref);
         if(var == null) {
            return null;
         }
         id = varList.size();
         idMap.put(ref, id);
         varList.add(var);
         return id;
      }

      LocalState acquireLocalState() {
         LocalState state = threadLocalState.get();
         long currentVersion = this.version;
         if(state == null || state.version != currentVersion) {
            if(state == null || state.regMasks.length != numVars) {
               state = new LocalState(currentVersion, baseCpuRegMask.clone(), baseAllocations.clone(), numVars);
               threadLocalState.set(state);
            } else {
               System.arraycopy(baseCpuRegMask, 0, state.regMasks, 0, numVars);
               System.arraycopy(baseAllocations, 0, state.allocations, 0, numVars);
               state.version = currentVersion;
            }
         }
         return state;
      }

      void recordBaselineStmt(Integer stmtIdxObj, int clobberMask, int weightedScore, int bytes) {
         if(stmtIdxObj == null) {
            return;
         }
         int stmtIdx = stmtIdxObj;
         if(stmtIdx >= 0 && stmtIdx < numStmts) {
            baseStmtClobberMask[stmtIdx] = clobberMask;
            baseStmtWeightedScore[stmtIdx] = weightedScore;
            baseStmtBytes[stmtIdx] = bytes;
            baseStmtValid[stmtIdx] = true;
         }
      }

      private void invalidateForVar(int varId) {
         int[] stmtIds = varToStmtIds[varId];
         for(int i = 0; i < stmtIds.length; i++) {
            baseStmtValid[stmtIds[i]] = false;
         }
         PhiTransitionMeta[] phiMetas = varToPhiMetas[varId];
         for(int i = 0; i < phiMetas.length; i++) {
            phiMetas[i].baseValid = false;
         }
      }

      void updateCombination(RegisterCombination combination) {
         boolean changed = false;
         for(Map.Entry<LiveRangeEquivalenceClass, Registers.Register> entry : combination.getAllocation().entrySet()) {
            Registers.Register reg = entry.getValue();
            byte regMask = toRegMask(reg);
            for(VariableRef varRef : entry.getKey().getVariables()) {
               Integer id = varIdMap.get(varRef);
               if(id != null && !Objects.equals(baseAllocations[id], reg)) {
                  baseAllocations[id] = reg;
                  baseCpuRegMask[id] = regMask;
                  invalidateForVar(id);
                  changed = true;
               }
            }
         }
         if(changed) {
            version++;
         }
      }

      synchronized void refreshFromVariables() {
         boolean changed = false;
         for(int i = 0; i < numVars; i++) {
            Registers.Register alloc = varsById[i].getAllocation();
            if(!Objects.equals(baseAllocations[i], alloc)) {
               baseAllocations[i] = alloc;
               baseCpuRegMask[i] = toRegMask(alloc);
               invalidateForVar(i);
               changed = true;
            }
         }
         if(changed) {
            version++;
         }
      }
   }

   static FastClobberIndex getOrBuildClobberIndex(Program program) {
      Pass4RegisterUpliftCombinations.UpliftProgramCache cache = program.getUpliftProgramCache();
      Map<LabelRef, PhiTransitions> currentPhiTransitions = program.getPhiTransitions();
      FastClobberIndex idx = cache.clobberIndex;
      if(idx != null && idx.phiTransitionsRef == currentPhiTransitions) {
         return idx;
      }
      synchronized(cache) {
         idx = cache.clobberIndex;
         if(idx == null || idx.phiTransitionsRef != currentPhiTransitions) {
            idx = new FastClobberIndex(program, currentPhiTransitions);
            cache.clobberIndex = idx;
         }
         return idx;
      }
   }

   /**
    * Pre-initializes and returns the cached CPU synthesizer for the program in a thread-safe manner.
    */
   static AsmFragmentTemplateSynthesizer getCpuSynthesizer(Program program) {
      Pass4RegisterUpliftCombinations.UpliftProgramCache cache = program.getUpliftProgramCache();
      AsmFragmentTemplateSynthesizer synth = cache.cpuSynthesizer;
      if(synth != null) {
         return synth;
      }
      synchronized(program.getAsmFragmentMasterSynthesizer()) {
         synth = cache.cpuSynthesizer;
         if(synth == null) {
            synth = program.getAsmFragmentMasterSynthesizer().getSynthesizer(program.getTargetCpu());
            cache.cpuSynthesizer = synth;
         }
         return synth;
      }
   }

   /**
    * Evaluate register allocation for a set of blocks: checks CPU register clobber
    * and computes weighted cycle score and byte size. Early-exits on the first clobber
    * violation or unknown fragment.
    */
   public static TrialEvalResult evaluateBlocks(
         Program program,
         Set<Graph.Block> blocks,
         Map<Integer, Double> multipliers,
         Collection<String> unknownFragments) {
      double[] stmtMultipliers = toStmtMultipliers(program, multipliers);
      return evaluateBlocks(program, blocks, stmtMultipliers, unknownFragments, null, null, false);
   }

   private static double[] toStmtMultipliers(Program program, Map<Integer, Double> multipliers) {
      if(multipliers == null) {
         return null;
      }
      if(multipliers == program.getUpliftProgramCache().loopMultipliers) {
         return Pass4RegisterUpliftCombinations.getStmtMultipliers(program);
      }
      int maxIdx = -1;
      for(Integer k : multipliers.keySet()) {
         if(k != null && k > maxIdx) {
            maxIdx = k;
         }
      }
      double[] arr = new double[maxIdx + 1];
      Arrays.fill(arr, 10.0);
      for(Map.Entry<Integer, Double> e : multipliers.entrySet()) {
         Integer k = e.getKey();
         Double v = e.getValue();
         if(k != null && k >= 0 && v != null) {
            arr[k] = v;
         }
      }
      return arr;
   }

   private static boolean hasOverriddenRef(int[] stmtRefVarIds, boolean[] overridden) {
      if(stmtRefVarIds == null) {
         return false;
      }
      for(int i = 0; i < stmtRefVarIds.length; i++) {
         if(overridden[stmtRefVarIds[i]]) {
            return true;
         }
      }
      return false;
   }

   static TrialEvalResult evaluateBlocks(
         Program program,
         Set<Graph.Block> blocks,
         double[] stmtMultipliers,
         Collection<String> unknownFragments,
         FastClobberIndex clobberIndex,
         FastClobberIndex.LocalState localState,
         boolean useBaselineCache) {
      EvalAcc acc = new EvalAcc();
      Set<PhiTransitions.PhiTransition> transitionsGenerated = Collections.newSetFromMap(new IdentityHashMap<>());
      Map<LabelRef, PhiTransitions> allPhiTransitions = program.getPhiTransitions();
      Graph graph = program.getGraph();
      byte[] localRegMasks = localState != null ? localState.regMasks : null;
      Registers.Register[] localAllocs = localState != null ? localState.allocations : null;
      boolean[] localOverridden = localState != null ? localState.overridden : null;
      boolean useLocalCache = !AsmFragmentTemplateUsages.TRACK_USAGES;
      ConcurrentHashMap<String, AsmFragmentTemplate> templateCache = useLocalCache
            ? program.getUpliftProgramCache().fragmentTemplates
            : null;

      try {
         for(Graph.Block block : blocks) {
            // 1. Entry conditional-successor phi transitions into this block
            PhiTransitions entryTransitions = allPhiTransitions.get(block.getLabel());
            if(entryTransitions != null) {
               for(Graph.Block fromBlock : entryTransitions.getFromBlocks()) {
                  PhiTransitions.PhiTransition transition = entryTransitions.getTransition(fromBlock);
                  if(!transitionsGenerated.contains(transition) && block.getLabel().equals(fromBlock.getConditionalSuccessor())) {
                     if(!evaluatePhiTransition(program, transition, block.getScope(), stmtMultipliers, acc, true, clobberIndex, localState, templateCache, useBaselineCache)) {
                        return FAILED_RESULT;
                     }
                     transitionsGenerated.add(transition);
                  }
               }
            }

            // 2. Non-PHI statements in the block
            for(Statement statement : block.getStatements()) {
               if(statement instanceof StatementPhiBlock) {
                  continue;
               }
               if(statement instanceof StatementCall call) {
                  Procedure toProcedure = program.getScope().getProcedure(call.getProcedure());
                  if(!toProcedure.isDeclaredIntrinsic() && Procedure.CallingConvention.PHI_CALL.equals(toProcedure.getCallingConvention())) {
                     Graph.Block callSuccessor = graph.getCallSuccessor(block);
                     if(callSuccessor != null && callSuccessor.hasPhiBlock()) {
                        PhiTransitions.PhiTransition transition = allPhiTransitions.get(callSuccessor.getLabel()).getTransition(block);
                        if(transitionsGenerated.contains(transition)) {
                           throw new InternalError("Error! JSR transition already generated. Must modify PhiTransitions code to ensure this does not happen.");
                        }
                        if(!evaluatePhiTransition(program, transition, block.getScope(), stmtMultipliers, acc, true, clobberIndex, localState, templateCache, useBaselineCache)) {
                           return FAILED_RESULT;
                        }
                        transitionsGenerated.add(transition);
                     }
                  }
               }
               if(useBaselineCache && clobberIndex != null && localState != null) {
                  Integer stmtIdxObj = statement.getIndex();
                  if(stmtIdxObj != null) {
                     int stmtIdx = stmtIdxObj;
                     if(stmtIdx >= 0 && stmtIdx < clobberIndex.numStmts && clobberIndex.baseStmtValid[stmtIdx]
                           && !hasOverriddenRef(clobberIndex.stmtReferencedVarIds[stmtIdx], localOverridden)) {
                        int clobberMask = clobberIndex.baseStmtClobberMask[stmtIdx];
                        if(clobberMask != 0 && hasStatementClobberProblem(program, statement, clobberMask, clobberIndex, localRegMasks)) {
                           return FAILED_RESULT;
                        }
                        int weighted = clobberIndex.baseStmtWeightedScore[stmtIdx];
                        int stmtBytes = clobberIndex.baseStmtBytes[stmtIdx];
                        acc.blockScore += weighted;
                        acc.totalScore += weighted;
                        acc.blockBytes += stmtBytes;
                        acc.totalBytes += stmtBytes;
                        continue;
                     }
                  }
               }
               evaluateStatementCost(program, block, statement, acc, clobberIndex, localAllocs);
               if(acc.clobberMask != 0 && hasStatementClobberProblem(program, statement, acc.clobberMask, clobberIndex, localRegMasks)) {
                  return FAILED_RESULT;
               }
               acc.addChunkCost(statement.getIndex(), stmtMultipliers);
            }

            // 3. Default-successor phi transition out of this block
            Graph.Block defaultSuccessor = graph.getDefaultSuccessor(block);
            if(defaultSuccessor != null && defaultSuccessor.hasPhiBlock()) {
               PhiTransitions.PhiTransition transition = allPhiTransitions.get(defaultSuccessor.getLabel()).getTransition(block);
               if(!transitionsGenerated.contains(transition)) {
                  if(!evaluatePhiTransition(program, transition, defaultSuccessor.getScope(), stmtMultipliers, acc, true, clobberIndex, localState, templateCache, useBaselineCache)) {
                     return FAILED_RESULT;
                  }
                  transitionsGenerated.add(transition);
               }
            }
         }
         return new TrialEvalResult(true, acc.totalScore, acc.totalBytes);
      } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
         if(unknownFragments != null) {
            unknownFragments.add(e.getFragmentSignature());
         }
         return FAILED_RESULT;
      }
   }

   /**
    * Compute per-block scores and bytes without clobber early-exit, used to initialize
    * and incrementally update {@link Pass4RegisterUpliftCombinations.CachedBlockScores}.
    */
   static void computeBlockScores(
         Program program,
         Collection<Graph.Block> blocks,
         double[] stmtMultipliers,
         Map<Graph.Block, Integer> outBlockScores,
         Map<Graph.Block, Integer> outBlockBytes,
         FastClobberIndex clobberIndex,
         boolean useBaselineCache) {
      EvalAcc acc = new EvalAcc();
      Set<PhiTransitions.PhiTransition> transitionsGenerated = Collections.newSetFromMap(new IdentityHashMap<>());
      Map<LabelRef, PhiTransitions> allPhiTransitions = program.getPhiTransitions();
      Graph graph = program.getGraph();
      boolean warnFragmentMissing = program.isWarnFragmentMissing();
      boolean useLocalCache = !AsmFragmentTemplateUsages.TRACK_USAGES;
      ConcurrentHashMap<String, AsmFragmentTemplate> templateCache = useLocalCache
            ? program.getUpliftProgramCache().fragmentTemplates
            : null;

      for(Graph.Block block : blocks) {
         acc.blockScore = 0;
         acc.blockBytes = 0;

         // 1. Entry conditional-successor phi transitions into this block
         PhiTransitions entryTransitions = allPhiTransitions.get(block.getLabel());
         if(entryTransitions != null) {
            for(Graph.Block fromBlock : entryTransitions.getFromBlocks()) {
               PhiTransitions.PhiTransition transition = entryTransitions.getTransition(fromBlock);
               if(!transitionsGenerated.contains(transition) && block.getLabel().equals(fromBlock.getConditionalSuccessor())) {
                  evaluatePhiTransition(program, transition, block.getScope(), stmtMultipliers, acc, false, clobberIndex, null, templateCache, useBaselineCache);
                  transitionsGenerated.add(transition);
               }
            }
         }

         // 2. Non-PHI statements in the block
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementPhiBlock) {
               continue;
            }
            try {
               if(statement instanceof StatementCall call) {
                  Procedure toProcedure = program.getScope().getProcedure(call.getProcedure());
                  if(!toProcedure.isDeclaredIntrinsic() && Procedure.CallingConvention.PHI_CALL.equals(toProcedure.getCallingConvention())) {
                     Graph.Block callSuccessor = graph.getCallSuccessor(block);
                     if(callSuccessor != null && callSuccessor.hasPhiBlock()) {
                        PhiTransitions.PhiTransition transition = allPhiTransitions.get(callSuccessor.getLabel()).getTransition(block);
                        if(transitionsGenerated.contains(transition)) {
                           throw new InternalError("Error! JSR transition already generated. Must modify PhiTransitions code to ensure this does not happen.");
                        }
                        evaluatePhiTransition(program, transition, block.getScope(), stmtMultipliers, acc, false, clobberIndex, null, templateCache, useBaselineCache);
                        transitionsGenerated.add(transition);
                     }
                  }
               }
               if(useBaselineCache && clobberIndex != null) {
                  Integer stmtIdxObj = statement.getIndex();
                  if(stmtIdxObj != null) {
                     int stmtIdx = stmtIdxObj;
                     if(stmtIdx >= 0 && stmtIdx < clobberIndex.numStmts && clobberIndex.baseStmtValid[stmtIdx]) {
                        int weighted = clobberIndex.baseStmtWeightedScore[stmtIdx];
                        int stmtBytes = clobberIndex.baseStmtBytes[stmtIdx];
                        acc.blockScore += weighted;
                        acc.totalScore += weighted;
                        acc.blockBytes += stmtBytes;
                        acc.totalBytes += stmtBytes;
                        continue;
                     }
                  }
               }
               evaluateStatementCost(program, block, statement, acc, null, null);
               int weighted = acc.addChunkCost(statement.getIndex(), stmtMultipliers);
               if(useBaselineCache && clobberIndex != null) {
                  clobberIndex.recordBaselineStmt(statement.getIndex(), acc.clobberMask, weighted, acc.bytes);
               }
            } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
               StatementSource statementSource = statement.getSource();
               if(warnFragmentMissing) {
                  String stmtFormat = statementSource != null ? statementSource.format() : "";
                  program.getLog().append("Warning! Unknown fragment for statement " + statement.toString(program, false) + "\nMissing ASM fragment " + e.getFragmentSignature() + "\n" + stmtFormat);
               } else {
                  String msg = "Unknown fragment for statement " + statement.toString(program, false) + "\nMissing ASM fragment " + e.getFragmentSignature();
                  if(statement instanceof StatementAssignment assign && Operators.DIVIDE.equals(assign.getOperator())) {
                     msg += "\nHint: Runtime division requires an ASM fragment (e.g. " + e.getFragmentSignature() + ".asm), or use div8u()/div16u() from <division.h>.";
                  }
                  throw new CompileError(msg, statementSource);
               }
            } catch(CompileError e) {
               if(e.getSource() == null) {
                  throw new CompileError(e.getMessage(), statement);
               }
               throw e;
            }
         }

         // 3. Default-successor phi transition out of this block
         Graph.Block defaultSuccessor = graph.getDefaultSuccessor(block);
         if(defaultSuccessor != null && defaultSuccessor.hasPhiBlock()) {
            PhiTransitions.PhiTransition transition = allPhiTransitions.get(defaultSuccessor.getLabel()).getTransition(block);
            if(!transitionsGenerated.contains(transition)) {
               evaluatePhiTransition(program, transition, defaultSuccessor.getScope(), stmtMultipliers, acc, false, clobberIndex, null, templateCache, useBaselineCache);
               transitionsGenerated.add(transition);
            }
         }

         outBlockScores.put(block, acc.blockScore);
         outBlockBytes.put(block, acc.blockBytes);
      }
   }

   /**
    * Evaluate the CPU register clobber of a single statement (with {@code genCallPhiEntry=false} semantics).
    * Returns {@code null} if an unknown fragment is encountered and {@code unknownFragments != null}.
    */
   public static CpuClobber evaluateStatementClobber(
         Program program,
         Graph.Block block,
         Statement statement,
         Collection<String> unknownFragments) {
      if(statement instanceof StatementPhiBlock) {
         return CpuClobber.CLOBBER_NONE;
      }
      EvalAcc acc = new EvalAcc();
      try {
         evaluateStatementCost(program, block, statement, acc, null, null);
         return toCpuClobber(acc.clobberMask);
      } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
         if(unknownFragments != null) {
            unknownFragments.add(e.getFragmentSignature());
            return null;
         }
         throw e;
      }
   }

   private static String getFastRegName(Registers.Register reg, Registers.Register firstBoundReg) {
      return switch(reg.getType()) {
         case REG_A -> "aa";
         case REG_X -> "xx";
         case REG_Y -> "yy";
         case REG_Z -> "zz";
         case ZP_MEM -> {
            if(firstBoundReg == null) {
               yield "z1";
            }
            Registers.RegisterType firstType = firstBoundReg.getType();
            if(firstType == Registers.RegisterType.ZP_MEM) {
               yield (((Registers.RegisterZpMem) reg).getZp() == ((Registers.RegisterZpMem) firstBoundReg).getZp()) ? "z1" : "z2";
            }
            yield (firstType == Registers.RegisterType.MAIN_MEM) ? "z2" : "z1";
         }
         case MAIN_MEM -> {
            if(firstBoundReg == null) {
               yield "m1";
            }
            Registers.RegisterType firstType = firstBoundReg.getType();
            if(firstType == Registers.RegisterType.MAIN_MEM) {
               yield reg.equals(firstBoundReg) ? "m1" : "m2";
            }
            yield (firstType == Registers.RegisterType.ZP_MEM) ? "m2" : "m1";
         }
         default -> null;
      };
   }

   private static boolean evaluatePhiTransition(
         Program program,
         PhiTransitions.PhiTransition transition,
         ScopeRef scope,
         double[] stmtMultipliers,
         EvalAcc acc,
         boolean checkClobber,
         FastClobberIndex clobberIndex,
         FastClobberIndex.LocalState localState,
         ConcurrentHashMap<String, AsmFragmentTemplate> templateCache,
         boolean useBaselineCache) {
      FastClobberIndex.PhiTransitionMeta meta = (clobberIndex != null)
            ? clobberIndex.phiTransitionMetas.get(transition)
            : null;
      if(useBaselineCache && meta != null && meta.baseValid
            && (localState == null || !hasOverriddenRef(meta.referencedVarIds, localState.overridden))) {
         if(checkClobber && meta.baseClobberMask != 0 && localState != null) {
            int[] assignMasks = meta.baseAssignClobberMasks;
            int[][] clobberVars = meta.clobberVarIds;
            byte[] regMasks = localState.regMasks;
            for(int idx = 0; idx < assignMasks.length; idx++) {
               int mask = assignMasks[idx];
               if(mask != 0) {
                  int[] aliveIds = clobberVars[idx];
                  for(int k = 0; k < aliveIds.length; k++) {
                     if((regMasks[aliveIds[k]] & mask) != 0) {
                        return false;
                     }
                  }
               }
            }
         }
         int weighted = meta.baseWeightedScore;
         int phiBytes = meta.baseBytes;
         acc.blockScore += weighted;
         acc.totalScore += weighted;
         acc.blockBytes += phiBytes;
         acc.totalBytes += phiBytes;
         return true;
      }

      List<PhiTransitions.PhiTransition.PhiAssignment> assignments = transition.getAssignments();
      Registers.Register[] localAllocs = localState != null ? localState.allocations : null;
      byte[] localRegMasks = localState != null ? localState.regMasks : null;
      boolean recordBaseline = useBaselineCache && !checkClobber && meta != null && localState == null;
      int transitionScore = 0;
      int transitionBytes = 0;
      int transitionClobberMask = 0;

      for(int i = 0; i < assignments.size(); i++) {
         PhiTransitions.PhiTransition.PhiAssignment assignment = assignments.get(i);
         int assignIdx = assignment.getAssignmentIdx();
         LValue lValue = assignment.getVariable();
         RValue rValue = assignment.getrValue();
         Statement phiBlock = assignment.getPhiBlock();

         AsmFragmentTemplate template = null;
         if(meta != null && localAllocs != null) {
            int lVarId = meta.lVarIds[assignIdx];
            int rVarId = meta.rVarIds[assignIdx];
            if(lVarId >= 0 && rVarId >= 0) {
               Registers.Register lReg = localAllocs[lVarId];
               Registers.Register rReg = localAllocs[rVarId];
               if(lReg != null && lReg.equals(rReg)) {
                  if(recordBaseline) meta.baseAssignClobberMasks[assignIdx] = 0;
                  continue;
               }
               if(templateCache != null && lReg != null && rReg != null) {
                  String lPrefix = meta.lTypePrefixes[assignIdx];
                  String rPrefix = meta.rTypePrefixes[assignIdx];
                  if(lPrefix != null && rPrefix != null) {
                     String lRegName = getFastRegName(lReg, null);
                     String rRegName = (lRegName != null) ? getFastRegName(rReg, lReg) : null;
                     if(rRegName != null) {
                        String sig = lPrefix + lRegName + "=" + rPrefix + rRegName;
                        AsmFragmentTemplate cached = templateCache.get(sig);
                        if(cached != null && cached != AsmFragmentTemplateCache.NO_SYNTHESIS) {
                           template = cached;
                        }
                     }
                  }
               }
            } else {
               if(isRegisterCopy(program, lValue, rValue, clobberIndex, localAllocs)) {
                  if(recordBaseline) meta.baseAssignClobberMasks[assignIdx] = 0;
                  continue;
               }
               if(templateCache != null && lVarId >= 0 && meta.rTypePrefixes[assignIdx] != null) {
                  Registers.Register lReg = localAllocs[lVarId];
                  String lPrefix = meta.lTypePrefixes[assignIdx];
                  if(lReg != null && lPrefix != null) {
                     String lRegName = getFastRegName(lReg, null);
                     if(lRegName != null) {
                        String sig = lPrefix + lRegName + "=" + meta.rTypePrefixes[assignIdx] + "c1";
                        AsmFragmentTemplate cached = templateCache.get(sig);
                        if(cached != null && cached != AsmFragmentTemplateCache.NO_SYNTHESIS) {
                           template = cached;
                        }
                     }
                  }
               }
            }
         } else {
            if(isRegisterCopy(program, lValue, rValue, clobberIndex, localAllocs)) {
               if(recordBaseline) meta.baseAssignClobberMasks[assignIdx] = 0;
               continue;
            }
         }

         if(template == null) {
            template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.assignment(lValue, rValue, program, scope));
         }
         int clobberMask = toClobberMask(template.getClobber());
         if(checkClobber && clobberMask != 0) {
            if(meta != null && localRegMasks != null) {
               int[] aliveIds = meta.clobberVarIds[assignIdx];
               for(int k = 0; k < aliveIds.length; k++) {
                  if((localRegMasks[aliveIds[k]] & clobberMask) != 0) {
                     return false;
                  }
               }
            } else if(hasPhiAssignmentClobberProblem(program, phiBlock, assignments, assignment, clobberMask)) {
               return false;
            }
         }
         acc.clobberMask = clobberMask;
         acc.cycles = template.getCycles();
         acc.bytes = template.getBytes();
         int weighted = acc.addChunkCost(phiBlock.getIndex(), stmtMultipliers);
         if(recordBaseline) {
            transitionScore += weighted;
            transitionBytes += acc.bytes;
            transitionClobberMask |= clobberMask;
            meta.baseAssignClobberMasks[assignIdx] = clobberMask;
         }
      }
      if(recordBaseline) {
         meta.baseClobberMask = transitionClobberMask;
         meta.baseWeightedScore = transitionScore;
         meta.baseBytes = transitionBytes;
         meta.baseValid = true;
      }
      return true;
   }

   private static void evaluateStatementCost(
         Program program,
         Graph.Block block,
         Statement statement,
         EvalAcc acc,
         FastClobberIndex clobberIndex,
         Registers.Register[] localAllocs) {
      if(statement instanceof StatementAssignment assignment) {
         LValue lValue = assignment.getlValue();
         if(assignment.getOperator() == null && assignment.getrValue1() == null
               && isRegisterCopy(program, lValue, assignment.getrValue2(), clobberIndex, localAllocs)) {
            acc.clobberMask = 0;
            acc.cycles = 0.0;
            acc.bytes = 0;
            return;
         }
         try {
            AsmFragmentTemplate template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.assignment(assignment, program));
            acc.clobberMask = toClobberMask(template.getClobber());
            acc.cycles = template.getCycles();
            acc.bytes = template.getBytes();
         } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
            throw e;
         } catch(CompileError e) {
            throw e;
         } catch(RuntimeException e) {
            throw new CompileError("Problem with source, runtime Exception: " + e.getMessage(), statement);
         }
      } else if(statement instanceof StatementConditionalJump condJump) {
         AsmFragmentTemplate template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.conditionalJump(condJump, block, program));
         acc.clobberMask = toClobberMask(template.getClobber());
         acc.cycles = template.getCycles();
         acc.bytes = template.getBytes();
      } else if(statement instanceof StatementCall call) {
         Procedure toProcedure = program.getScope().getProcedure(call.getProcedure());
         Procedure fromProcedure = program.getProcedure(block);
         if(toProcedure.isDeclaredIntrinsic()) {
            if(Pass1ByteXIntrinsicRewrite.INTRINSIC_MAKELONG4.equals(toProcedure.getFullName())) {
               AsmFragmentTemplate template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.makelong4(call, program));
               acc.clobberMask = toClobberMask(template.getClobber());
               acc.cycles = template.getCycles();
               acc.bytes = template.getBytes();
            } else {
               throw new CompileError("Intrinsic procedure not supported " + toProcedure.toString(program));
            }
         } else if(Procedure.CallingConvention.PHI_CALL.equals(toProcedure.getCallingConvention())) {
            Bank.CallingDistance callingDistance = Bank.CallingDistance.forCall(fromProcedure.getBank(), toProcedure.getBank());
            if(Bank.CallingDistance.NEAR.equals(callingDistance)) {
               // jsr abs: 6 cycles, 3 bytes, only P/S clobbered
               acc.clobberMask = 0;
               acc.cycles = 6.0;
               acc.bytes = 3;
            } else {
               AsmFragmentTemplate template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.callBanked(toProcedure, callingDistance, program));
               acc.clobberMask = toClobberMask(template.getClobber());
               acc.cycles = template.getCycles();
               acc.bytes = template.getBytes();
            }
         } else if(Procedure.CallingConvention.STACK_CALL.equals(toProcedure.getCallingConvention())) {
            Bank.CallingDistance callingDistance = Bank.CallingDistance.forCall(fromProcedure.getBank(), toProcedure.getBank());
            if(Bank.CallingDistance.NEAR.equals(callingDistance)) {
               // jsr abs: 6 cycles, 3 bytes, only P/S clobbered
               acc.clobberMask = 0;
               acc.cycles = 6.0;
               acc.bytes = 3;
            } else {
               throw new CompileError("Stack Call procedure not supported in banked mode " + toProcedure.toString(program));
            }
         } else {
            acc.clobberMask = 0;
            acc.cycles = 0.0;
            acc.bytes = 0;
         }
      } else if(statement instanceof StatementCallExecute call) {
         ProcedureRef procedureRef = call.getProcedure();
         if(procedureRef != null) {
            Procedure toProcedure = program.getScope().getProcedure(procedureRef);
            Procedure fromProcedure = program.getProcedure(block);
            Bank.CallingDistance callingDistance = Bank.CallingDistance.forCall(fromProcedure.getBank(), toProcedure.getBank());
            if(!Bank.CallingDistance.NEAR.equals(callingDistance)) {
               throw new CompileError("Stack Call procedure not supported in banked mode " + toProcedure.toString(program));
            }
         }
         AsmFragmentTemplate template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.call(call, 1, program));
         acc.cycles = template.getCycles();
         acc.bytes = template.getBytes();
         if(!(call.getProcedureRVal() instanceof ProcedureRef)) {
            acc.clobberMask = MASK_ALL;
         } else {
            acc.clobberMask = toClobberMask(template.getClobber());
         }
      } else if(statement instanceof StatementExprSideEffect exprSideEffect) {
         AsmFragmentTemplate template = resolveTemplate(program, AsmFragmentInstanceSpecBuilder.exprSideEffect(exprSideEffect, program));
         acc.clobberMask = toClobberMask(template.getClobber());
         acc.cycles = template.getCycles();
         acc.bytes = template.getBytes();
      } else if(statement instanceof StatementReturn) {
         Procedure procedure = null;
         ScopeRef scope = block.getScope();
         if(!scope.equals(ScopeRef.ROOT)) {
            procedure = program.getScope().getProcedure((ProcedureRef) scope);
         }
         if(procedure == null || procedure.getInterruptType() == null) {
            // rts: 6 cycles, 1 byte, only P/S clobbered
            acc.clobberMask = 0;
            acc.cycles = 6.0;
            acc.bytes = 1;
         } else {
            AsmProgram miniAsm = new AsmProgram(program.getTargetCpu());
            new Pass4CodeGeneration(program, false, false, true).generateStatementAsm(miniAsm, block, statement, false);
            acc.clobberMask = toClobberMask(miniAsm.getClobber());
            acc.cycles = miniAsm.getCycles();
            acc.bytes = miniAsm.getBytes();
         }
      } else if(statement instanceof StatementAsm) {
         AsmProgram miniAsm = new AsmProgram(program.getTargetCpu());
         new Pass4CodeGeneration(program, false, false, true).generateStatementAsm(miniAsm, block, statement, false);
         acc.clobberMask = toClobberMask(miniAsm.getClobber());
         acc.cycles = miniAsm.getCycles();
         acc.bytes = miniAsm.getBytes();
      } else if(statement instanceof StatementKickAsm kasm) {
         int asmBytes = 256;
         if(kasm.getBytes() != null) {
            ConstantValue kasmBytes = (ConstantValue) kasm.getBytes();
            ConstantLiteral literal = kasmBytes.calculateLiteral(program.getScope());
            asmBytes = ((ConstantInteger) literal).getInteger().intValue();
         }
         double asmCycles = 256.0;
         if(kasm.getCycles() != null) {
            ConstantValue kasmCycles = (ConstantValue) kasm.getCycles();
            ConstantLiteral literal = kasmCycles.calculateLiteral(program.getScope());
            asmCycles = ((ConstantInteger) literal).getInteger().doubleValue();
         }
         CpuClobber declared = kasm.getDeclaredClobber();
         acc.clobberMask = declared != null ? toClobberMask(declared) : 0;
         acc.cycles = asmCycles;
         acc.bytes = asmBytes;
      } else if(statement instanceof StatementCallPointer) {
         throw new InternalError("Statement not supported " + statement);
      } else {
         acc.clobberMask = 0;
         acc.cycles = 0.0;
         acc.bytes = 0;
      }
   }

   private static AsmFragmentTemplate resolveTemplate(Program program, AsmFragmentInstanceSpec spec) {
      AsmFragmentTemplateSynthesizer cpuSynthesizer = getCpuSynthesizer(program);
      Pass4RegisterUpliftCombinations.UpliftProgramCache cache = program.getUpliftProgramCache();
      ConcurrentHashMap<String, AsmFragmentTemplate> templateCache = cache.fragmentTemplates;
      boolean useLocalCache = !AsmFragmentTemplateUsages.TRACK_USAGES;
      String initialSignature = spec.getSignature();

      while(true) {
         String signature = spec.getSignature();
         AsmFragmentTemplate cached = useLocalCache ? templateCache.get(signature) : null;
         if(cached == AsmFragmentTemplateCache.NO_SYNTHESIS) {
            // Known missing signature
         } else if(cached != null) {
            return cached;
         } else {
            try {
               AsmFragmentInstance instance = cpuSynthesizer.getFragmentInstance(spec, program.getLog());
               AsmFragmentTemplate template = (AsmFragmentTemplate) FRAGMENT_TEMPLATE_HANDLE.get(instance);
               if(useLocalCache) {
                  templateCache.putIfAbsent(signature.intern(), template);
               }
               return template;
            } catch(AsmFragmentTemplateSynthesizer.UnknownFragmentException e) {
               if(useLocalCache) {
                  templateCache.putIfAbsent(signature.intern(), AsmFragmentTemplateCache.NO_SYNTHESIS);
               }
            }
         }

         if(spec.hasNextVariation()) {
            spec.nextVariation();
            if(program.getLog().isVerboseFragmentLog()) {
               program.getLog().append("Fragment not found " + signature + ". Attempting another variation " + spec.getSignature());
            }
         } else {
            throw new AsmFragmentTemplateSynthesizer.UnknownFragmentException(initialSignature);
         }
      }
   }

   private static boolean hasStatementClobberProblem(
         Program program,
         Statement statement,
         int clobberMask,
         FastClobberIndex clobberIndex,
         byte[] localRegMasks) {
      if(clobberIndex != null && localRegMasks != null) {
         Integer stmtIdx = statement.getIndex();
         if(stmtIdx != null) {
            int idx = stmtIdx;
            if(idx >= 0 && idx < clobberIndex.stmtAliveNonAssignedVarIds.length) {
               int[] aliveIds = clobberIndex.stmtAliveNonAssignedVarIds[idx];
               if(aliveIds != null) {
                  for(int i = 0; i < aliveIds.length; i++) {
                     if((localRegMasks[aliveIds[i]] & clobberMask) != 0) {
                        return true;
                     }
                  }
                  return false;
               }
            }
         }
      }
      return hasStatementClobberProblem(program, statement, clobberMask);
   }

   private static boolean hasStatementClobberProblem(Program program, Statement statement, int clobberMask) {
      Collection<VariableRef> aliveVars = program.getLiveRangeVariablesEffective().getAliveEffective(statement);
      if(aliveVars.isEmpty()) {
         return false;
      }
      SymbolInfos symbolInfos = program.getSymbolInfos();
      for(VariableRef aliveVar : aliveVars) {
         Registers.Register reg = symbolInfos.getVariable(aliveVar).getAllocation();
         if(!isRegisterClobbered(reg, clobberMask)) {
            continue;
         }
         if(!isStatementAssignedVar(statement, aliveVar)) {
            return true;
         }
      }
      return false;
   }

   private static boolean hasPhiAssignmentClobberProblem(
         Program program,
         Statement phiBlock,
         List<PhiTransitions.PhiTransition.PhiAssignment> assignments,
         PhiTransitions.PhiTransition.PhiAssignment current,
         int clobberMask) {
      int currentIdx = current.getAssignmentIdx();
      VariableRef currentVar = current.getVariable();
      SymbolInfos symbolInfos = program.getSymbolInfos();

      // Build assignedVars matching Pass4AssertNoCpuClobber:
      // all phiBlock assigned vars minus all other phiTransition variables.
      Set<VariableRef> extraAssigned = null;
      if(phiBlock instanceof StatementPhiBlock pb) {
         List<StatementPhiBlock.PhiVariable> phiVars = pb.getPhiVariables();
         if(phiVars.size() > assignments.size()) {
            extraAssigned = Pass4RegisterUpliftPotentialRegisterAnalysis.getAssignedVars(phiBlock);
            for(int i = 0; i < assignments.size(); i++) {
               PhiTransitions.PhiTransition.PhiAssignment other = assignments.get(i);
               if(other.getAssignmentIdx() != currentIdx) {
                  extraAssigned.remove(other.getVariable());
               }
            }
         }
      }

      // 1. Check aliveEffective at the phiBlock
      Collection<VariableRef> baseAlive = program.getLiveRangeVariablesEffective().getAliveEffective(phiBlock);
      for(VariableRef aliveVar : baseAlive) {
         Registers.Register reg = symbolInfos.getVariable(aliveVar).getAllocation();
         if(!isRegisterClobbered(reg, clobberMask)) {
            continue;
         }
         if(extraAssigned != null ? !extraAssigned.contains(aliveVar) : !aliveVar.equals(currentVar)) {
            return true;
         }
      }

      // 2. Check referenced vars of later phi assignments in the same transition
      for(int i = 0; i < assignments.size(); i++) {
         PhiTransitions.PhiTransition.PhiAssignment other = assignments.get(i);
         if(other.getAssignmentIdx() > currentIdx) {
            Collection<VariableRef> laterRefs = VariableReferenceInfos.getReferencedVars(other.getrValue());
            for(VariableRef aliveVar : laterRefs) {
               Registers.Register reg = symbolInfos.getVariable(aliveVar).getAllocation();
               if(!isRegisterClobbered(reg, clobberMask)) {
                  continue;
               }
               if(extraAssigned != null ? !extraAssigned.contains(aliveVar) : !aliveVar.equals(currentVar)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private static boolean isStatementAssignedVar(Statement statement, VariableRef target) {
      if(statement instanceof StatementLValue stmtLVal) {
         return containsAssignedVar(stmtLVal.getlValue(), target);
      }
      return false;
   }

   private static boolean containsAssignedVar(RValue lValue, VariableRef target) {
      if(lValue instanceof VariableRef) {
         return lValue.equals(target);
      }
      if(lValue instanceof ValueList valueList) {
         List<RValue> list = valueList.getList();
         for(int i = 0; i < list.size(); i++) {
            if(containsAssignedVar(list.get(i), target)) {
               return true;
            }
         }
      }
      return false;
   }

   static byte toRegMask(Registers.Register reg) {
      if(reg == null || reg.isMem()) {
         return 0;
      }
      return switch(reg.getType()) {
         case REG_A -> MASK_A;
         case REG_X -> MASK_X;
         case REG_Y -> MASK_Y;
         case REG_Z -> MASK_Z;
         default -> 0;
      };
   }

   private static boolean isRegisterClobbered(Registers.Register reg, int clobberMask) {
      if(reg == null || reg.isMem()) {
         return false;
      }
      return switch(reg.getType()) {
         case REG_A -> (clobberMask & MASK_A) != 0;
         case REG_X -> (clobberMask & MASK_X) != 0;
         case REG_Y -> (clobberMask & MASK_Y) != 0;
         case REG_Z -> (clobberMask & MASK_Z) != 0;
         default -> false;
      };
   }

   private static int toClobberMask(AsmFragmentClobber clobber) {
      int mask = 0;
      if(clobber.isClobberA()) mask |= MASK_A;
      if(clobber.isClobberX()) mask |= MASK_X;
      if(clobber.isClobberY()) mask |= MASK_Y;
      if(clobber.isClobberZ()) mask |= MASK_Z;
      return mask;
   }

   private static int toClobberMask(CpuClobber clobber) {
      int mask = 0;
      if(clobber.isRegisterA()) mask |= MASK_A;
      if(clobber.isRegisterX()) mask |= MASK_X;
      if(clobber.isRegisterY()) mask |= MASK_Y;
      if(clobber.isRegisterZ()) mask |= MASK_Z;
      return mask;
   }

   private static CpuClobber toCpuClobber(int clobberMask) {
      return CLOBBER_CACHE[clobberMask & MASK_ALL];
   }

   private static Registers.Register getRegister(
         Program program,
         RValue rValue,
         FastClobberIndex clobberIndex,
         Registers.Register[] localAllocs) {
      if(rValue instanceof VariableRef rValueRef) {
         if(clobberIndex != null && localAllocs != null) {
            Integer id = clobberIndex.varIdMap.get(rValueRef);
            if(id != null) {
               return localAllocs[id];
            }
         }
         return program.getSymbolInfos().getVariable(rValueRef).getAllocation();
      } else if(rValue instanceof CastValue castValue) {
         return getRegister(program, castValue.getValue(), clobberIndex, localAllocs);
      } else {
         return null;
      }
   }

   private static boolean isRegisterCopy(
         Program program,
         LValue lValue,
         RValue rValue,
         FastClobberIndex clobberIndex,
         Registers.Register[] localAllocs) {
      Registers.Register lReg = getRegister(program, lValue, clobberIndex, localAllocs);
      return lReg != null && lReg.equals(getRegister(program, rValue, clobberIndex, localAllocs));
   }
}
