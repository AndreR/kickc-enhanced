package dk.camelot64.kickc.model;

import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.passes.Pass2AliasElimination;

import java.util.*;

/**
 * Precomputed interference matrix between live range equivalence classes for Pass 4.
 * Two equivalence classes interfere if there exists any statement where non-aliased variables
 * from both classes are simultaneously alive (or defined).
 * Assigning the same register to interfering equivalence classes creates an overlapping allocation.
 */
public class LiveRangeInterference {

   private final Program program;
   private final LiveRangeEquivalenceClassSet equivalenceClassSet;
   private final List<LiveRangeEquivalenceClass> classes;
   private final Map<LiveRangeEquivalenceClass, Integer> ecToIndex;
   private final BitSet[] interference;
   private final Map<Registers.Register, BitSet> classesByRegister;

   public LiveRangeInterference(Program program) {
      this.program = program;
      this.equivalenceClassSet = program.getLiveRangeEquivalenceClassSet();
      this.classes = new ArrayList<>(equivalenceClassSet.getEquivalenceClasses());
      int n = classes.size();
      this.ecToIndex = new HashMap<>(n * 2);
      for(int i = 0; i < n; i++) {
         ecToIndex.put(classes.get(i), i);
      }
      this.interference = new BitSet[n];
      for(int i = 0; i < n; i++) {
         interference[i] = new BitSet(n);
      }
      this.classesByRegister = new HashMap<>();
      for(int i = 0; i < n; i++) {
         LiveRangeEquivalenceClass ec = classes.get(i);
         Registers.Register reg = ec.getRegister();
         if(reg != null) {
            classesByRegister.computeIfAbsent(reg, k -> new BitSet(n)).set(i);
         }
      }
      buildInterference();
   }

   private void buildInterference() {
      VariableReferenceInfos variableReferenceInfos = program.getVariableReferenceInfos();
      LiveRangeVariablesEffective liveRangeVariablesEffective = program.getLiveRangeVariablesEffective();

      for(Statement statement : program.getGraph().getAllStatements()) {
         LiveRangeVariablesEffective.AliveCombinations aliveCombinations = liveRangeVariablesEffective.getAliveCombinations(statement);
         final Collection<VariableRef> stmtDefinedVars = variableReferenceInfos.getDefinedVars(statement);

         for(LiveRangeVariablesEffective.AliveCombination combination : aliveCombinations.getAll()) {
            Collection<VariableRef> aliveAtStmt = combination.getEffectiveAliveAtStmt();
            if(!aliveAtStmt.containsAll(stmtDefinedVars)) {
               aliveAtStmt = new LinkedHashSet<>(aliveAtStmt);
               aliveAtStmt.addAll(stmtDefinedVars);
            }
            if(aliveAtStmt.size() <= 1) continue;

            Pass2AliasElimination.Aliases aliasesAtStmt = combination.getEffectiveAliasesAtStmt();

            List<VariableRef> varList = (aliveAtStmt instanceof List) ? (List<VariableRef>) aliveAtStmt : new ArrayList<>(aliveAtStmt);
            int varCount = varList.size();

            // Pre-map equivalence class indices to avoid repeated HashMap lookups in the inner loop
            int[] ecIdx = new int[varCount];
            LiveRangeEquivalenceClass[] ecArr = new LiveRangeEquivalenceClass[varCount];
            for(int i = 0; i < varCount; i++) {
               LiveRangeEquivalenceClass ec = equivalenceClassSet.getEquivalenceClass(varList.get(i));
               ecArr[i] = ec;
               ecIdx[i] = (ec != null) ? ecToIndex.getOrDefault(ec, -1) : -1;
            }

            for(int i = 0; i < varCount; i++) {
               int idxI = ecIdx[i];
               if(idxI < 0) continue;
               LiveRangeEquivalenceClass ecI = ecArr[i];
               VariableRef varI = varList.get(i);

               for(int j = i + 1; j < varCount; j++) {
                  int idxJ = ecIdx[j];
                  if(idxJ < 0 || idxI == idxJ) continue;

                  if(interference[idxI].get(idxJ)) continue;

                  VariableRef varJ = varList.get(j);
                  LiveRangeEquivalenceClass ecJ = ecArr[j];

                  boolean isAlias = false;
                  if(aliasesAtStmt != null) {
                     Pass2AliasElimination.AliasSet aliasSetJ = aliasesAtStmt.findAliasSet(varJ);
                     if(aliasSetJ != null) {
                        for(VariableRef aliasVar : aliasSetJ.getVars()) {
                           if(ecI.contains(aliasVar)) {
                              isAlias = true;
                              break;
                           }
                        }
                     }
                     if(!isAlias) {
                        Pass2AliasElimination.AliasSet aliasSetI = aliasesAtStmt.findAliasSet(varI);
                        if(aliasSetI != null) {
                           for(VariableRef aliasVar : aliasSetI.getVars()) {
                              if(ecJ.contains(aliasVar)) {
                                 isAlias = true;
                                 break;
                              }
                           }
                        }
                     }
                  }

                  if(!isAlias) {
                     interference[idxI].set(idxJ);
                     interference[idxJ].set(idxI);
                  }
               }
            }
         }
      }
   }

   public boolean isAllocationOverlapping(RegisterCombination combination) {
      if(combination == null) {
         for(Map.Entry<Registers.Register, BitSet> entry : classesByRegister.entrySet()) {
            BitSet set = entry.getValue();
            for(int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
               if(interference[i].intersects(set)) {
                  return true;
               }
            }
         }
         return false;
      }

      Map<LiveRangeEquivalenceClass, Registers.Register> newAlloc = combination.getAllocation();
      if(newAlloc.isEmpty()) return false;

      // 1. Check combinations among themselves
      int size = newAlloc.size();
      if(size > 1) {
         List<Map.Entry<LiveRangeEquivalenceClass, Registers.Register>> entries = new ArrayList<>(newAlloc.entrySet());
         for(int i = 0; i < size; i++) {
            Map.Entry<LiveRangeEquivalenceClass, Registers.Register> e1 = entries.get(i);
            Integer idx1 = ecToIndex.get(e1.getKey());
            if(idx1 == null) continue;
            Registers.Register reg1 = e1.getValue();

            for(int j = i + 1; j < size; j++) {
               Map.Entry<LiveRangeEquivalenceClass, Registers.Register> e2 = entries.get(j);
               if(reg1.equals(e2.getValue())) {
                  Integer idx2 = ecToIndex.get(e2.getKey());
                  if(idx2 != null && interference[idx1].get(idx2)) {
                     return true;
                  }
               }
            }
         }
      }

      // 2. Check each combination class against non-combination classes sharing the same register
      for(Map.Entry<LiveRangeEquivalenceClass, Registers.Register> entry : newAlloc.entrySet()) {
         Integer idx = ecToIndex.get(entry.getKey());
         if(idx == null) continue;
         Registers.Register newReg = entry.getValue();

         BitSet existing = classesByRegister.get(newReg);
         if(existing != null && !existing.isEmpty()) {
            for(int i = existing.nextSetBit(0); i >= 0; i = existing.nextSetBit(i + 1)) {
               if(newAlloc.containsKey(classes.get(i))) continue;
               if(interference[idx].get(i)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public boolean interferes(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2) {
      Integer idx1 = ecToIndex.get(ec1);
      Integer idx2 = ecToIndex.get(ec2);
      if(idx1 == null || idx2 == null) return false;
      return interference[idx1].get(idx2);
   }

   public void updateRegister(LiveRangeEquivalenceClass ec, Registers.Register newReg) {
      Integer idx = ecToIndex.get(ec);
      if(idx == null) return;
      for(BitSet set : classesByRegister.values()) {
         set.clear(idx);
      }
      if(newReg != null) {
         classesByRegister.computeIfAbsent(newReg, k -> new BitSet(classes.size())).set(idx);
      }
   }

   public void updateCombination(RegisterCombination combination) {
      for(Map.Entry<LiveRangeEquivalenceClass, Registers.Register> entry : combination.getAllocation().entrySet()) {
         updateRegister(entry.getKey(), entry.getValue());
      }
   }

   public void consolidate(LiveRangeEquivalenceClass ec1, LiveRangeEquivalenceClass ec2) {
      Integer idx1 = ecToIndex.get(ec1);
      Integer idx2 = ecToIndex.get(ec2);
      if(idx1 == null || idx2 == null) return;

      // 1. Any class that interfered with ec2 now interferes with ec1
      interference[idx1].or(interference[idx2]);
      interference[idx1].clear(idx1); // never interfere with self

      // 2. Update all other classes to interfere with idx1 if they interfered with idx2
      int n = classes.size();
      for(int k = 0; k < n; k++) {
         if(interference[k].get(idx2)) {
            interference[k].set(idx1);
            interference[k].clear(idx2);
         }
      }
      interference[idx2].clear();

      // 3. Remove idx2 from classesByRegister, ensure idx1 has ec1's register
      for(BitSet set : classesByRegister.values()) {
         set.clear(idx2);
      }
      if(ec1.getRegister() != null) {
         classesByRegister.computeIfAbsent(ec1.getRegister(), k -> new BitSet(n)).set(idx1);
      }

      ecToIndex.remove(ec2);
   }
}
