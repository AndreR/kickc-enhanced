package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.ProcedureCompilation;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorBinary;
import dk.camelot64.kickc.model.operators.OperatorUnary;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementLValue;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.model.DominatorsBlock;
import dk.camelot64.kickc.model.DominatorsGraph;
import dk.camelot64.kickc.model.symbols.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler Pass identifying constant if() conditions
 */
public class Pass2ConstantIfs extends Pass2SsaOptimization {

   public Pass2ConstantIfs(Program program) {
      super(program);
   }

   /** Per-procedure lazy caches for dominance-based constant condition folding. */
   private static class ProcDominanceContext {
      final List<Graph.Block> procBlocks;
      Map<LabelRef, Graph.Block> localBlockMap;
      Map<LabelRef, List<Graph.Block>> localPredMap;
      Set<VariableRef> writtenVars;

      ProcDominanceContext(List<Graph.Block> procBlocks) {
         this.procBlocks = procBlocks;
      }

      Map<LabelRef, Graph.Block> getBlockMap() {
         if(localBlockMap == null) {
            localBlockMap = new HashMap<>(procBlocks.size() * 2);
            for(Graph.Block b : procBlocks) {
               localBlockMap.put(b.getLabel(), b);
            }
         }
         return localBlockMap;
      }

      Map<LabelRef, List<Graph.Block>> getPredMap() {
         if(localPredMap == null) {
            localPredMap = new HashMap<>(procBlocks.size() * 2);
            for(Graph.Block other : procBlocks) {
               if(other.getDefaultSuccessor() != null) {
                  localPredMap.computeIfAbsent(other.getDefaultSuccessor(), k -> new ArrayList<>()).add(other);
               }
               if(other.getConditionalSuccessor() != null) {
                  localPredMap.computeIfAbsent(other.getConditionalSuccessor(), k -> new ArrayList<>()).add(other);
               }
               if(other.getCallSuccessor() != null) {
                  localPredMap.computeIfAbsent(other.getCallSuccessor(), k -> new ArrayList<>()).add(other);
               }
            }
         }
         return localPredMap;
      }

      Set<VariableRef> getWrittenVars() {
         if(writtenVars == null) {
            writtenVars = new HashSet<>();
            for(Graph.Block b : procBlocks) {
               for(Statement stmt : b.getStatements()) {
                  if(stmt instanceof StatementLValue sa && sa.getlValue() instanceof VariableRef vr) {
                     writtenVars.add(vr);
                  }
               }
            }
         }
         return writtenVars;
      }

      void invalidatePreds() {
         localPredMap = null;
      }
   }

   /**
    * Identify constant conditions in if()'s
    *
    * @return true optimization was performed. false if no optimization was possible.
    */
   @Override
   public boolean step() {
      boolean modified = false;

      for(ProcedureCompilation procComp : getProgram().getProcedureCompilations()) {
         ControlFlowGraph procGraph = procComp.getGraph();
         if(procGraph == null) continue;
         List<Graph.Block> procBlocks = procGraph.getAllBlocks();
         ProcDominanceContext ctx = null;

         for(var block : procBlocks) {
            ListIterator<Statement> stmtIt = block.getStatements().listIterator();
            while(stmtIt.hasNext()) {
               Statement statement = stmtIt.next();
               if(statement instanceof StatementConditionalJump conditional) {
                  if(ctx == null) {
                     ctx = new ProcDominanceContext(procBlocks);
                  }
                  ConstantLiteral literal = findConditionLiteral(conditional);
                  if(literal == null) {
                     literal = findConditionLiteralInterval(conditional);
                  }
                  if(literal == null) {
                     literal = findConditionLiteralDominance(block, conditional, ctx);
                  }
                  if(literal instanceof ConstantBool) {
                     // Condition is a constant boolean
                     if(((ConstantBool) literal).getBool()) {
                        // Always true - replace default successor and drop conditional jump
                        Graph.Block defSuccBlock = resolveLocalOrGlobalBlock(block.getDefaultSuccessor(), ctx);
                        if(defSuccBlock != null) {
                           Pass2EliminateUnusedBlocks.removePhiRValues(block.getLabel(), defSuccBlock, getLog());
                        }
                        block.setDefaultSuccessor(conditional.getDestination());
                        getLog().append("if() condition always true - replacing block destination " + conditional.toString(getProgram(), false));
                        stmtIt.remove();
                        block.setConditionalSuccessor(null);
                        ctx.invalidatePreds();
                        modified = true;
                     } else {
                        // Always false - drop the conditional jump
                        Graph.Block condSuccBlock = resolveLocalOrGlobalBlock(block.getConditionalSuccessor(), ctx);
                        if(condSuccBlock != null) {
                           Pass2EliminateUnusedBlocks.removePhiRValues(block.getLabel(), condSuccBlock, getLog());
                        }
                        getLog().append("if() condition always false - eliminating " + conditional.toString(getProgram(), false));
                        stmtIt.remove();
                        block.setConditionalSuccessor(null);
                        ctx.invalidatePreds();
                        modified = true;
                     }
                  }
               }
            }
         }
      }
      return modified;
   }

   private Graph.Block resolveLocalOrGlobalBlock(LabelRef label, ProcDominanceContext ctx) {
      if(label == null || SymbolRef.PROCEXIT_BLOCK_NAME.equals(label.getFullName())) {
         return null;
      }
      Graph.Block b = ctx.getBlockMap().get(label);
      if(b == null) {
         b = getGraph().getBlock(label);
      }
      return b;
   }

   /**
    * If the condition always evaluates to constant true or false this finds tha value.
    *
    * @param conditional The conditional
    * @return The literal value of the condition
    */
   private ConstantLiteral findConditionLiteral(StatementConditionalJump conditional) {
      ConstantValue constValue1 = Pass2ConstantIdentification.getConstant(conditional.getrValue1());
      Operator operator = conditional.getOperator();
      ConstantValue constValue2 = Pass2ConstantIdentification.getConstant(conditional.getrValue2());
      try {

         // If all values are constant find the literal condition value
         if(conditional.getrValue1() == null && operator == null && constValue2 != null) {
            // Constant condition
            return constValue2.calculateLiteral(getProgramScope());
         } else if(conditional.getrValue1() == null && operator != null && constValue2 != null) {
            // Constant unary condition
            ConstantValue constVal = Pass2ConstantIdentification.createUnary((OperatorUnary) operator, constValue2);
            return constVal.calculateLiteral(getProgramScope());
         } else if(constValue1 != null && operator != null && constValue2 != null) {
            // Constant binary condition
            ConstantValue constVal = Pass2ConstantIdentification.createBinary(constValue1, (OperatorBinary) operator, constValue2, getProgramScope());
            return constVal.calculateLiteral(getProgramScope());
         }

      } catch(ConstantNotLiteral e) {
         // not literal - keep as null
      }
      return null;
   }

   /**
    * Examine the intervals of the conditions to see if they
    *
    * @param conditional The conditional
    * @return The literal value of the condition. Null if not literal.
    */
   private ConstantLiteral findConditionLiteralInterval(StatementConditionalJump conditional) {
      if(conditional.getrValue1() != null && conditional.getrValue2() != null) {
         IntValueInterval interval1 = getInterval(conditional.getrValue1());
         IntValueInterval interval2 = getInterval(conditional.getrValue2());
         if(interval1 != null && interval2 != null) {
            if(Operators.LT.equals(conditional.getOperator())) {
               if(interval1.maxValue < interval2.minValue) {
                  return new ConstantBool(true);
               } else if(interval1.minValue >= interval2.maxValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
            } else if(Operators.LE.equals(conditional.getOperator())) {
               if(interval1.maxValue <= interval2.minValue) {
                  return new ConstantBool(true);
               } else if(interval1.minValue > interval2.maxValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
            } else if(Operators.GT.equals(conditional.getOperator())) {
               if(interval1.minValue > interval2.maxValue) {
                  return new ConstantBool(true);
               } else if(interval1.maxValue <= interval2.minValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
            } else if(Operators.GE.equals(conditional.getOperator())) {
               if(interval1.minValue >= interval2.maxValue) {
                  return new ConstantBool(true);
               } else if(interval1.maxValue < interval2.minValue) {
                  return new ConstantBool(false);
               } else {
                  return null;
               }
         } else if(Operators.EQ.equals(conditional.getOperator())) {
            if(interval1.minValue > interval2.maxValue) {
               return new ConstantBool(false);
            } else if(interval1.maxValue < interval2.minValue) {
               return new ConstantBool(false);
            } else {
               return null;
            }
         } else if(Operators.NEQ.equals(conditional.getOperator())) {
            if(interval1.minValue > interval2.maxValue) {
               return new ConstantBool(true);
            } else if(interval1.maxValue < interval2.minValue) {
               return new ConstantBool(true);
            } else {
               return null;
            }
            }
         }
      }
      return null;
   }

   /** Interval of integer types. */
   static class IntValueInterval {
      long minValue;
      long maxValue;

      public IntValueInterval(long minValue, long maxValue) {
         this.minValue = minValue;
         this.maxValue = maxValue;
      }

   }

   private IntValueInterval getInterval(RValue rValue) {
      ConstantValue constValue = Pass2ConstantIdentification.getConstant(rValue);
      if(constValue != null) {
         try {
            ConstantLiteral constantLiteral = constValue.calculateLiteral(getProgramScope());
            if(constantLiteral instanceof ConstantInteger) {
               Long value = ((ConstantInteger) constantLiteral).getInteger();
               return new IntValueInterval(value, value);
            }
         } catch(ConstantNotLiteral e) {
            // not literal - keep as null
         }
      }
      // Not constant - find the interval of the type
      SymbolType symbolType = SymbolTypeInference.inferType(getProgramScope(), rValue);
      if(symbolType instanceof SymbolTypeIntegerFixed) {
         SymbolTypeIntegerFixed typeIntegerFixed = (SymbolTypeIntegerFixed) symbolType;
         return new IntValueInterval(typeIntegerFixed.getMinValue(), typeIntegerFixed.getMaxValue());
      } else if(symbolType.equals(SymbolType.UNUMBER)) {
         // Positive number
         return new IntValueInterval(0L, Long.MAX_VALUE);
      }
      // Not a constant integer
      return null;
   }

   private ConstantLiteral findConditionLiteralDominance(Graph.Block blockB, StatementConditionalJump conditionalB, ProcDominanceContext ctx) {
      DominatorsGraph dominators = getProgram().getDominators();
      if(dominators == null) return null;
      DominatorsBlock bDoms = dominators.getDominators(blockB.getLabel());
      if(bDoms == null) return null;

      if(!isImmutableCondition(blockB, conditionalB, ctx)) return null;

      Map<LabelRef, Graph.Block> localBlockMap = ctx.getBlockMap();
      Map<LabelRef, List<Graph.Block>> localPredMap = ctx.getPredMap();

      for(Graph.Block blockA : ctx.procBlocks) {
         if(blockA.equals(blockB)) continue;
         StatementConditionalJump conditionalA = getConditionalJump(blockA);
         if(conditionalA == null || !isImmutableCondition(blockA, conditionalA, ctx)) continue;

         LabelRef condSuccA = conditionalA.getDestination();
         LabelRef defaultSuccA = blockA.getDefaultSuccessor();

         // Check true branch of A
         if(condSuccA != null && bDoms.contains(condSuccA)) {
            Graph.Block condBlock = localBlockMap.get(condSuccA);
            if(condBlock != null) {
               List<Graph.Block> preds = localPredMap.get(condSuccA);
               if(preds != null && preds.size() == 1 && preds.get(0).equals(blockA)) {
                  if(areConditionsIdentical(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " identical to " + blockA.getLabel() + " on true branch -> folding TRUE");
                     return new ConstantBool(true);
                  } else if(areConditionsInverse(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " inverse to " + blockA.getLabel() + " on true branch -> folding FALSE");
                     return new ConstantBool(false);
                  }
               }
            }
         }

         // Check false branch of A
         if(defaultSuccA != null && bDoms.contains(defaultSuccA)) {
            Graph.Block defaultBlock = localBlockMap.get(defaultSuccA);
            if(defaultBlock != null) {
               List<Graph.Block> preds = localPredMap.get(defaultSuccA);
               if(preds != null && preds.size() == 1 && preds.get(0).equals(blockA)) {
                  if(areConditionsIdentical(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " identical to " + blockA.getLabel() + " on false branch -> folding FALSE");
                     return new ConstantBool(false);
                  } else if(areConditionsInverse(conditionalA, conditionalB)) {
                     getLog().append("RCE: Condition in " + blockB.getLabel() + " inverse to " + blockA.getLabel() + " on false branch -> folding TRUE");
                     return new ConstantBool(true);
                  }
               }
            }
         }
      }
      return null;
   }

   private StatementConditionalJump getConditionalJump(Graph.Block block) {
      for(Statement stmt : block.getStatements()) {
         if(stmt instanceof StatementConditionalJump) {
            return (StatementConditionalJump) stmt;
         }
      }
      return null;
   }

   private boolean isImmutableCondition(Graph.Block block, StatementConditionalJump conditional, ProcDominanceContext ctx) {
      return isImmutableValue(block, conditional.getrValue1(), ctx) && isImmutableValue(block, conditional.getrValue2(), ctx);
   }

   private boolean isImmutableValue(Graph.Block block, RValue val, ProcDominanceContext ctx) {
      if(val == null) return true;
      if(val instanceof ConstantValue) return true;
      if(val instanceof VariableRef varRef) {
         Variable variable = getProgramScope().getVariable(varRef);
         if(variable == null) return false;
         if(variable.isVolatile()) return false;
         if(variable.getKind() != Variable.Kind.LOAD_STORE && variable.getKind() != Variable.Kind.PHI_MASTER) {
            return true;
         }
         // Check if variable has no writes in containing procedure
         dk.camelot64.kickc.model.symbols.Scope blockScope = getProgramScope().getScope(block.getScope());
         dk.camelot64.kickc.model.symbols.Procedure proc = blockScope != null ? blockScope.getContainingProcedure() : null;
         if(proc != null) {
            return !ctx.getWrittenVars().contains(varRef);
         }
      }
      return false;
   }

   private boolean areConditionsIdentical(StatementConditionalJump a, StatementConditionalJump b) {
      if(Objects.equals(a.getrValue1(), b.getrValue1()) &&
            Objects.equals(a.getOperator(), b.getOperator()) &&
            Objects.equals(a.getrValue2(), b.getrValue2())) {
         return true;
      }
      // Commutative EQ / NEQ
      if(Operators.EQ.equals(a.getOperator()) && Operators.EQ.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.NEQ.equals(a.getOperator()) && Operators.NEQ.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      // Flipped inequality: a < b is identical to b > a
      if(Operators.LT.equals(a.getOperator()) && Operators.GT.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.GT.equals(a.getOperator()) && Operators.LT.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.LE.equals(a.getOperator()) && Operators.GE.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      if(Operators.GE.equals(a.getOperator()) && Operators.LE.equals(b.getOperator())) {
         return Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1());
      }
      return false;
   }

   private boolean areConditionsInverse(StatementConditionalJump a, StatementConditionalJump b) {
      Operator opA = a.getOperator();
      Operator opB = b.getOperator();
      if(opA == null || opB == null) return false;

      // Same operands, inverse operator
      if(Objects.equals(a.getrValue1(), b.getrValue1()) && Objects.equals(a.getrValue2(), b.getrValue2())) {
         if(Operators.EQ.equals(opA) && Operators.NEQ.equals(opB)) return true;
         if(Operators.NEQ.equals(opA) && Operators.EQ.equals(opB)) return true;
         if(Operators.LT.equals(opA) && Operators.GE.equals(opB)) return true;
         if(Operators.GE.equals(opA) && Operators.LT.equals(opB)) return true;
         if(Operators.LE.equals(opA) && Operators.GT.equals(opB)) return true;
         if(Operators.GT.equals(opA) && Operators.LE.equals(opB)) return true;
      }
      // Swapped operands
      if(Objects.equals(a.getrValue1(), b.getrValue2()) && Objects.equals(a.getrValue2(), b.getrValue1())) {
         if(Operators.EQ.equals(opA) && Operators.NEQ.equals(opB)) return true;
         if(Operators.NEQ.equals(opA) && Operators.EQ.equals(opB)) return true;
         if(Operators.LT.equals(opA) && Operators.LE.equals(opB)) return true;
         if(Operators.LE.equals(opA) && Operators.LT.equals(opB)) return true;
         if(Operators.GT.equals(opA) && Operators.GE.equals(opB)) return true;
         if(Operators.GE.equals(opA) && Operators.GT.equals(opB)) return true;
      }
      return false;
   }

}
