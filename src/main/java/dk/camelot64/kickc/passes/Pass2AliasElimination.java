package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ControlFlowBlock;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.InternalError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.passes.utils.VarAssignments;

import java.util.*;

/**
 * Compiler Pass eliminating alias assignments
 */
public class Pass2AliasElimination extends Pass2SsaOptimization {

   public Pass2AliasElimination(Program program) {
      super(program);
   }

   /**
    * Eliminate alias assignments replacing them with the aliassed variable.
    */
   @Override
   public boolean step() {
      VarAssignments.Index varAssignmentsIndex = new VarAssignments.Index(getGraph());
      final Aliases aliases = findAliases(getProgram(), varAssignmentsIndex);
      if(aliases.isEmpty()) {
         return false;
      }

      fixAliasSources(aliases, varAssignmentsIndex);
      removeAliasAssignments(aliases);
      replaceVariables(aliases.getReplacements(getProgramScope()));
      for(AliasSet aliasSet : aliases.getAliasSets()) {
         getLog().append("Alias " + aliasSet.toString(getProgram()));
      }
      deleteSymbols(getProgramScope(), aliases.getSymbolsToRemove(getProgramScope()));
      return true;
   }


   private static Aliases findAliases(Program program, VarAssignments.Index varAssignmentsIndex) {
      Aliases candidates = findAliasesCandidates(program, varAssignmentsIndex);
      if(candidates.isEmpty()) {
         return candidates;
      }
      cleanupCandidates(candidates, program);
      cleanupCandidateVolatiles(candidates, program);
      candidates.rebuildIndex();
      return candidates;
   }

   // Remove all candidates that are used after assignment in phi blocks
   private static void cleanupCandidates(Aliases candidates, Program program) {
      if(candidates.isEmpty()) {
         return;
      }
      List<StatementPhiBlock> phiBlocks = new ArrayList<>();
      for(var block : program.getGraph().getAllBlocks()) {
         if(block.hasPhiBlock() && block.getPhiBlock().getPhiVariables().size() > 1) {
            phiBlocks.add(block.getPhiBlock());
         }
      }
      if(phiBlocks.isEmpty()) {
         return;
      }
      ListIterator<AliasSet> aliasSetListIterator = candidates.getAliasSets().listIterator();
      while(aliasSetListIterator.hasNext()) {
         AliasSet aliasSet = aliasSetListIterator.next();
         boolean removeSet = false;
         for(StatementPhiBlock phi : phiBlocks) {
            boolean lMatch = false;
            for(StatementPhiBlock.PhiVariable phiVariable : phi.getPhiVariables()) {
               if(lMatch) {
                  if(aliasSet.contains(phiVariable.getVariable())) {
                     // Assigning inside tha alias set again - no need to check the variables
                     continue;
                  }
                  for(StatementPhiBlock.PhiRValue phiRValue : phiVariable.getValues()) {
                     RValue rValue = phiRValue.getrValue();
                     if(aliasSet.contains(rValue)) {
                        removeSet = true;
                        break;
                     }
                  }
               } else {
                  if(aliasSet.contains(phiVariable.getVariable())) {
                     lMatch = true;
                  }
               }
            }
            if(removeSet) break;
         }
         if(removeSet) {
            program.getLog().append("Alias candidate removed (phi-usage) " + aliasSet.toString(program));
            aliasSetListIterator.remove();
         }
      }
   }

   // Remove all candidates that are volatile and not assigned to the same variable
   private static void cleanupCandidateVolatiles(Aliases candidates, Program program) {
      ListIterator<AliasSet> aliasSetListIterator = candidates.getAliasSets().listIterator();
      while(aliasSetListIterator.hasNext()) {
         AliasSet aliasSet = aliasSetListIterator.next();
         ProgramScope programScope = program.getScope();
         // Examine if any volatile variables are in the alias
         boolean anyVolatile = false;
         boolean sameBaseVar = true;
         String unversionedFullName = null;
         for(VariableRef variableRef : aliasSet.getVars()) {
            Variable variable = programScope.getVariable(variableRef);
            if(variable.isVolatile() || variable.isKindLoadStore()) {
               anyVolatile = true;
            }
            if(unversionedFullName == null) {
               unversionedFullName = variableRef.getFullNameUnversioned();
            } else if(!unversionedFullName.equals(variableRef.getFullNameUnversioned())) {
               sameBaseVar = false;
            }
         }
         if(anyVolatile & !sameBaseVar) {
            program.getLog().append("Alias candidate removed (volatile)" + aliasSet.toString(program));
            aliasSetListIterator.remove();
         }
      }
   }


   /**
    * Find variables which are aliases of other variables.
    *
    * @return A bunch of alias sets
    */
   private static Aliases findAliasesCandidates(final Program program, VarAssignments.Index varAssignmentsIndex) {
      Aliases aliases = new Aliases();
      for(var block : program.getGraph().getAllBlocks()) {
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementAssignment) {
               StatementAssignment assignment = (StatementAssignment) statement;
               if(assignment.getlValue() instanceof VariableRef) {
                  VariableRef variable = (VariableRef) assignment.getlValue();
                  if(assignment.getrValue1() == null && assignment.getOperator() == null && assignment.getrValue2() instanceof VariableRef) {
                     // Alias assignment
                     VariableRef alias = (VariableRef) assignment.getrValue2();
                     List<VarAssignments.VarAssignment> assignments = varAssignmentsIndex.get(alias, program.getScope());
                     if(assignments.size() == 0)
                        throw new InternalError("Error! Var is never assigned! " + alias);
                     else if(assignments.size() > 1)
                        // Multiple assignments exist
                        continue;
                     // assignments.size()==1
                     VarAssignments.VarAssignment varAssignment = assignments.get(0);
                     if(VarAssignments.VarAssignment.Type.INIT_VALUE.equals(varAssignment.type)) {
                        aliases.add(variable, alias);
                     } else {
                        // Examine if the alias is assigned inside another scope
                        ScopeRef varAssignmentScope = block.getScope();
                        ScopeRef aliasAssignmentScope = varAssignment.block.getScope();
                        if(!alias.isIntermediate() && (!varAssignmentScope.equals(aliasAssignmentScope) || !variable.getScopeNames().equals(alias.getScopeNames()))) {
                           if(program.getLog().isVerboseNonOptimization()) {
                              program.getLog().append("Not aliassing across scopes: " + variable + " " + alias);
                           }
                        } else {
                           aliases.add(variable, alias);
                        }
                     }
                  }
               }
            } else if(statement instanceof StatementPhiBlock) {
               StatementPhiBlock phi = (StatementPhiBlock) statement;
               for(StatementPhiBlock.PhiVariable phiVariable : phi.getPhiVariables()) {
                  VariableRef variable = phiVariable.getVariable();
                  VariableRef alias = null;
                  for(StatementPhiBlock.PhiRValue phiRValue : phiVariable.getValues()) {
                     if(alias == null) {
                        // First rValue
                        if(phiRValue.getrValue() instanceof VariableRef) {
                           alias = (VariableRef) phiRValue.getrValue();
                           if(variable.equals(alias)) {
                              if(program.getLog().isVerboseNonOptimization()) {
                                 program.getLog().append("Not aliassing identity: " + variable + " " + alias);
                              }
                              alias = null;
                              break;
                           }
                           List<VarAssignments.VarAssignment> assignments = varAssignmentsIndex.get(alias, program.getScope());
                           if(assignments.size() == 0)
                              throw new InternalError("Error! Var is never assigned! " + variable);
                           if(assignments.size() > 1) {
                              // Multiple assignments exist
                              alias = null;
                              break;
                           }
                           // assignments.size()==1
                           VarAssignments.VarAssignment varAssignment = assignments.get(0);
                           if(!VarAssignments.VarAssignment.Type.INIT_VALUE.equals(varAssignment.type)) {
                              // Examine if the alias is assigned inside another scope
                              ScopeRef varAssignmentScope = block.getScope();
                              ScopeRef aliasAssignmentScope = varAssignment.block.getScope();
                              if(!alias.isIntermediate() && (!varAssignmentScope.equals(aliasAssignmentScope) || !variable.getScopeNames().equals(alias.getScopeNames()))) {
                                 if(program.getLog().isVerboseNonOptimization()) {
                                    program.getLog().append("Not aliassing across scopes: " + variable + " " + alias);
                                 }
                                 alias = null;
                                 break;
                              }
                           }


                           /*
                           // Examine if the alis is assigned inside another scope
                           ControlFlowBlock aliasAssignmentBlock = program.getGraph().getAssignmentBlock(alias);
                           ScopeRef aliasScope = aliasAssignmentBlock.getScope();
                           ScopeRef varScope = block.getScope();
                           if(!varScope.equals(aliasScope) || !variable.getScopeNames().equals(alias.getScopeNames())) {
                              if(program.getLog().isVerboseNonOptimization()) {
                                 program.getLog().append("Not aliassing across scopes: " + variable + " " + alias);
                              }
                              alias = null;
                              break;
                           } else if(variable.equals(alias)) {
                              if(program.getLog().isVerboseNonOptimization()) {
                                 program.getLog().append("Not aliassing identity: " + variable + " " + alias);
                              }
                              alias = null;
                              break;
                           }
                           */
                        } else {
                           // Not aliasing non-variables
                           break;
                        }
                     } else {
                        // rValue 2-n
                        if(!alias.equals(phiRValue.getrValue())) {
                           // Not aliasing if any rValue is not identical
                           alias = null;
                           break;
                        }
                     }
                  }
                  if(alias != null) {
                     aliases.add(variable, alias);
                  }

               }
            }
         }
      }
      return aliases;
   }

   /**
    * Fix the statement source of alias-assignments to be the largest of the sources of any alias
    *
    * @param aliases The aliases
    */
   private void fixAliasSources(Aliases aliases, VarAssignments.Index varAssignmentsIndex) {
      for(AliasSet aliasSet : aliases.getAliasSets()) {
         // Find the best statement source among the aliases
         StatementSource bestSource = null;
         List<Statement> assignments = new ArrayList<>();
         for(VariableRef aliasVar : aliasSet.getVars()) {
            final List<VarAssignments.VarAssignment> varAssignments = varAssignmentsIndex.get(aliasVar, getProgramScope());
            if(varAssignments.size()!=1)
               continue;
            final VarAssignments.VarAssignment varAssignment = varAssignments.get(0);
            if(!VarAssignments.VarAssignment.Type.STATEMENT_LVALUE.equals(varAssignment.type))
               continue;
            Statement assignment = varAssignment.statementLValue;
            if(assignment != null) {
               assignments.add(assignment);
               StatementSource source = assignment.getSource();
               if(bestSource == null) {
                  bestSource = source;
               } else if(source != null) {
                  if(source.contains(bestSource)) {
                     bestSource = source;
                  } else if(!bestSource.contains(source)) {
                     // The sources do not contain each other
                     bestSource = null;
                     assignments = null;
                     break;
                  }
               }
            }
         }
         if(bestSource != null) {
            // Found a best source - update all statements
            for(Statement assignment : assignments) {
               assignment.setSource(bestSource);
            }
         }
      }
   }

   /**
    * Remove all assignments that just assign an alias to itself
    *
    * @param aliases The aliases
    */
   private void removeAliasAssignments(Aliases aliases) {
      for(var block : getGraph().getAllBlocks()) {
         for(Iterator<Statement> iterator = block.getStatements().iterator(); iterator.hasNext(); ) {
            Statement statement = iterator.next();
            if(statement instanceof StatementAssignment) {
               StatementAssignment assignment = (StatementAssignment) statement;
               AliasSet aliasSet = aliases.findAliasSet(assignment.getlValue());
               if(aliasSet != null) {
                  if((assignment.getrValue1() == null) && (assignment.getOperator() == null) && aliasSet.contains(assignment.getrValue2())) {
                     iterator.remove();
                  }
               }
            } else if(statement instanceof StatementPhiBlock) {
               boolean modified = false;
               StatementPhiBlock phiBlock = (StatementPhiBlock) statement;
               Iterator<StatementPhiBlock.PhiVariable> variableIterator = phiBlock.getPhiVariables().iterator();
               while(variableIterator.hasNext()) {
                  StatementPhiBlock.PhiVariable phiVariable = variableIterator.next();
                  AliasSet aliasSet = aliases.findAliasSet(phiVariable.getVariable());
                  if(aliasSet != null && phiVariable.getValues().size() > 0) {
                     boolean remove = true;
                     for(StatementPhiBlock.PhiRValue phiRValue : phiVariable.getValues()) {
                        if(!aliasSet.contains(phiRValue.getrValue())) {
                           remove = false;
                           break;
                        }
                     }
                     if(remove) {
                        variableIterator.remove();
                        modified = true;
                     }
                  }
               }
               if(modified && phiBlock.getPhiVariables().size() == 0) {
                  iterator.remove();
               }
            }
         }
      }
   }

   public static class Aliases {

      private List<AliasSet> aliases;
      private Map<VariableRef, AliasSet> varToSet;

      public Aliases() {
         this.aliases = new ArrayList<>();
         this.varToSet = new HashMap<>();
      }

      public Aliases(Aliases aliases) {
         this.aliases = new ArrayList<>();
         this.varToSet = new HashMap<>();
         for(AliasSet aliasSet : aliases.getAliasSets()) {
            AliasSet copySet = new AliasSet(aliasSet);
            this.aliases.add(copySet);
            for(VariableRef var : copySet.getVars()) {
               this.varToSet.put(var, copySet);
            }
         }
      }

      public void rebuildIndex() {
         varToSet.clear();
         for(AliasSet aliasSet : aliases) {
            for(VariableRef var : aliasSet.getVars()) {
               varToSet.put(var, aliasSet);
            }
         }
      }

      public boolean isEmpty() {
         return aliases.isEmpty();
      }

      List<VariableRef> getSymbolsToRemove(ProgramScope scope) {
         ArrayList<VariableRef> eliminates = new ArrayList<>();
         for(AliasSet alias : aliases) {
            eliminates.addAll(alias.getEliminateVars(scope));
         }
         return eliminates;
      }

      Map<VariableRef, VariableRef> getReplacements(ProgramScope scope) {
         HashMap<VariableRef, VariableRef> replacements = new LinkedHashMap<>();
         for(AliasSet aliasSet : aliases) {
            VariableRef keepVar = aliasSet.getKeepVar(scope);
            for(VariableRef var : aliasSet.getEliminateVars(scope)) {
               if(!var.equals(keepVar)) {
                  replacements.put(var, keepVar);
               }
            }
         }
         return replacements;
      }

      public int size() {
         return aliases.size();
      }

      public void add(VariableRef var1, VariableRef var2) {
         AliasSet aliasSet1 = findAliasSet(var1);
         AliasSet aliasSet2 = findAliasSet(var2);
         if(aliasSet1 != null) {
            if(aliasSet2 != null) {
               if(aliasSet1 != aliasSet2) {
                  aliasSet1.addAll(aliasSet2);
                  for(VariableRef var : aliasSet2.getVars()) {
                     varToSet.put(var, aliasSet1);
                  }
                  aliases.remove(aliasSet2);
               }
            } else {
               aliasSet1.add(var2);
               varToSet.put(var2, aliasSet1);
            }
         } else {
            if(aliasSet2 != null) {
               aliasSet2.add(var1);
               varToSet.put(var1, aliasSet2);
            } else {
               AliasSet newSet = new AliasSet();
               newSet.add(var1);
               newSet.add(var2);
               aliases.add(newSet);
               varToSet.put(var1, newSet);
               varToSet.put(var2, newSet);
            }
         }
      }

      AliasSet findAliasSet(LValue lValue) {
         if(lValue instanceof VariableRef) {
            return varToSet.get((VariableRef) lValue);
         }
         return null;
      }

      List<AliasSet> getAliasSets() {
         return aliases;
      }

      public void addAll(Aliases aliases) {
         for(AliasSet aliasSet : aliases.getAliasSets()) {
            List<VariableRef> vars = aliasSet.getVars();
            VariableRef first = null;
            for(VariableRef var : vars) {
               if(first == null) {
                  first = var;
               } else {
                  add(first, var);
               }
            }
         }
      }

      public String toString(Program program) {
         StringBuilder str = new StringBuilder();
         str.append("{ ");
         for(AliasSet aliasSet : getAliasSets()) {
            str.append("{ ");
            str.append(aliasSet.toString(program));
            str.append("} ");
         }
         str.append("} ");
         return str.toString();
      }
   }

   public static class AliasSet {

      private List<VariableRef> vars;
      private Set<VariableRef> varSet;

      AliasSet() {
         this.vars = new ArrayList<>();
         this.varSet = new HashSet<>();
      }

      AliasSet(AliasSet aliasSet) {
         this.vars = new ArrayList<>(aliasSet.getVars());
         this.varSet = new HashSet<>(aliasSet.getVars());
      }

      public void add(VariableRef variable) {
         vars.add(variable);
         varSet.add(variable);
      }

      public boolean contains(RValue rValue) {
         if(rValue instanceof VariableRef) {
            return varSet.contains(rValue);
         } else {
            return false;
         }
      }

      public String toString(Program program) {
         StringBuilder str = new StringBuilder();
         str.append(getKeepVar(program.getScope()).toString(null));
         str.append(" = ");
         for(VariableRef var : getEliminateVars(program.getScope())) {
            str.append(var.toString(null)).append(" ");
         }
         return str.toString();
      }

      public List<VariableRef> getVars() {
         return vars;
      }

      public void addAll(AliasSet aliasSet) {
         vars.addAll(aliasSet.getVars());
         varSet.addAll(aliasSet.getVars());
      }

      VariableRef getKeepVar(ProgramScope scope) {
         // Score all base names (without versions for versioned vars, full name for intermediates)
         int maxScore = 0;
         String maxName = null;
         Map<String, Integer> varNameScore = new LinkedHashMap<>();
         for(VariableRef var : vars) {
            String name;
            int score;
            Variable variable = scope.getVariable(var);
            if(variable.isNoModify() || variable.isKindConstant()) {
               name = var.getFullNameUnversioned();
               score = 100;
            } else if(var.isVersion()) {
               name = var.getFullNameUnversioned();
               score = 4 - var.getScopeDepth();
            } else {
               // must be intermediate
               name = var.getFullName();
               score = 2 - var.getScopeDepth();
            }
            Integer nameScore = varNameScore.get(name);
            if(nameScore == null) {
               nameScore = 0;
            }
            nameScore = nameScore + score;
            varNameScore.put(name, nameScore);
            if(nameScore > maxScore) {
               maxName = name;
               maxScore = nameScore;
            }
         }
         // Find first var with highest scoring name
         List<VariableRef> vars = new ArrayList<>(this.vars);
         vars.sort(Comparator.comparing(SymbolRef::getFullName));
         for(VariableRef var : vars) {
            if(var.isVersion()) {
               if(maxName.equals(var.getFullNameUnversioned())) {
                  return var;
               }
            } else {
               if(maxName.equals(var.getFullName())) {
                  return var;
               }
            }
         }

         throw new RuntimeException("Keep variable unexpectedly not found!");

      }

      List<VariableRef> getEliminateVars(ProgramScope scope) {
         List<VariableRef> eliminate = new ArrayList<>();
         VariableRef keepVar = getKeepVar(scope);
         for(VariableRef var : vars) {
            if(!var.equals(keepVar)) {
               eliminate.add(var);
            }
         }
         return eliminate;
      }

      public void remove(RValue rValue) {
         if(rValue instanceof VariableRef) {
            vars.remove(rValue);
         }
      }

   }


}
