package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.values.VariableRef;
import dk.camelot64.kickc.model.symbols.Scope;

import java.util.*;

/*** For each non-uplifted equivalence class attempt to put it in a register */
public class Pass4RegisterUpliftRemains extends Pass2Base {

   public Pass4RegisterUpliftRemains(Program program) {
      super(program);
   }

   public void performUplift(int maxCombinations) {
      performUplift(maxCombinations, null);
   }

   public void performUplift(int maxCombinations, Pass4RegisterUpliftCombinations.CachedBlockScores cache) {
      LiveRangeEquivalenceClassSet equivalenceClassSet = getProgram().getLiveRangeEquivalenceClassSet();
      List<LiveRangeEquivalenceClass> equivalenceClasses = new ArrayList<>(equivalenceClassSet.getEquivalenceClasses());
      final VariableRegisterWeights registerWeights = getProgram().getVariableRegisterWeights();
      Collections.sort(equivalenceClasses, (o1, o2) -> Double.compare(registerWeights.getTotalWeight(o2), registerWeights.getTotalWeight(o1)));

      Set<String> unknownFragments = Collections.synchronizedSet(new LinkedHashSet<>());
      if(cache == null) {
         cache = new Pass4RegisterUpliftCombinations.CachedBlockScores();
         cache.init(getProgram());
      }

      for(LiveRangeEquivalenceClass equivalenceClass : equivalenceClasses) {
         Registers.Register register = equivalenceClass.getRegister();
         if(register.isMem() && register.getBytes()==1) {
            List<Registers.Register> potentials = getProgram().getRegisterPotentials().getPotentialRegisters(equivalenceClass);
            boolean hasCpuRegister = false;
            if(potentials != null) {
               for(Registers.Register p : potentials) {
                  if(!p.isMem()) {
                     hasCpuRegister = true;
                     break;
                  }
               }
            }
            if(!hasCpuRegister) {
               continue;
            }
            getLog().append("Attempting to uplift remaining variables in" + equivalenceClass);
            RegisterCombinationIterator combinationIterator = new RegisterCombinationIterator(Arrays.asList(equivalenceClass), getProgram().getRegisterPotentials());
            VariableRef variableRef = equivalenceClass.getVariables().get(0);
            Scope testedScope = getProgram().getSymbolInfos().getVariable(variableRef).getScope();
            Pass4RegisterUpliftCombinations.chooseBestUpliftCombination(combinationIterator, maxCombinations, unknownFragments, testedScope.getRef(), getProgram(), cache);
         }
      }

      if(unknownFragments.size() > 0) {
         getLog().append("MISSING FRAGMENTS");
         for(String unknownFragment : unknownFragments) {
            getLog().append("  " + unknownFragment);
         }
      }

   }

}
