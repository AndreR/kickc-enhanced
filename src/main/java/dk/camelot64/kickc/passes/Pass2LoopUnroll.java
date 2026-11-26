package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.*;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.passes.utils.Unroller;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Unroll Loops declared as inline or via unroll pragmas. */
public class Pass2LoopUnroll extends Pass2SsaOptimization {

   public Pass2LoopUnroll(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      // Check post-peel loop tails (-2) or counted unroll loops (> 0) that already have 0 remaining iterations on entry
      NaturalLoop zeroTripLoop = findAndClearZeroTripLoop(getProgram());
      if(zeroTripLoop != null) {
         Graph.Block loopHeadBlock = getProgram().getGraph().getBlock(zeroTripLoop.getHead());
         getLog().append("Eliminating zero-trip unrolled loop tail " + zeroTripLoop);
         unrollHeadBlock(loopHeadBlock, zeroTripLoop, getProgram());
         return true;
      }

      // Look for loops to unroll
      List<NaturalLoop> unrollLoops = findUnrollLoops(getProgram());
      // Is there any unrolling to do?
      if(unrollLoops.isEmpty()) {
         return false;
      }
      // Choose the candidate loop with the fewest blocks (and if several have the same number of blocks the first one encountered)
      NaturalLoop unrollLoop = chooseUnrollLoop(unrollLoops);
      getLog().append("Unrolling loop " + unrollLoop);

      // The original loop becomes the first iteration - the new copy becomes "the rest of the loop"
      Unroller.UnrollStrategy loopUnrollStrategy = new Unroller.UnrollStrategy() {
         @Override
         public TransitionHandling getEntryStrategy(LabelRef from, LabelRef to) {
            return TransitionHandling.TO_ORIGINAL;
         }

         @Override
         public TransitionHandling getInternalStrategy(LabelRef from, LabelRef to) {
            if(unrollLoop.getHead().equals(to)) {
               return TransitionHandling.TO_COPY;
            } else {
               return TransitionHandling.TO_BOTH;
            }
         }
      };

      // Unroll the first iteration of the loop
      Unroller unroller = new Unroller(getProgram(), unrollLoop, loopUnrollStrategy);
      unroller.unroll();
      // Mark the original loop as unrolled
      markOriginalUnrolled(unrollLoop, getProgram());
      return true;
   }

   /**
    * Update the declaredUnroll / wasUnrolled / unrollFactor information of the original loop blocks
    *
    * @param unrollLoop The (original) loop being unrolled
    */
   private static void markOriginalUnrolled(NaturalLoop unrollLoop, Program program) {
      for(var block : unrollLoop.getBlocks(program.getGraph())) {
         boolean exitsLoop = !unrollLoop.getBlocks().contains(block.getConditionalSuccessor())
               || !unrollLoop.getBlocks().contains(block.getDefaultSuccessor());
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementConditionalJump conditionalJump) {
               if(conditionalJump.isDeclaredUnroll()) {
                  // Mark as unrolled - to ensure it is removed before unrolling more
                  conditionalJump.setWasUnrolled(true);
                  // Remove unroll declaration - now only the "rest" of the loop needs unrolling
                  conditionalJump.setDeclaredUnroll(false);
                  conditionalJump.setUnrollFactor(null);
               } else if(exitsLoop && conditionalJump.getUnrollFactor() != null && conditionalJump.getUnrollFactor() > 0) {
                  // Counted #pragma unroll(N): clear remaining budget on the peeled original iteration
                  conditionalJump.setUnrollFactor(null);
               }
            }
         }
      }
   }


   /**
    * Choose which loop to unroll first from a candidate set.
    * The smallest loop (fewest control flow blocks) is chosen.
    * If multiple loops have the same size the first one is chosen.
    *
    * @param unrollLoopCandidates All loops that are declared to be unrolled
    * @return The loop to unroll first.
    */
   private static NaturalLoop chooseUnrollLoop(List<NaturalLoop> unrollLoopCandidates) {
      NaturalLoop unrollLoop = null;
      for(NaturalLoop unrollLoopCandidate : unrollLoopCandidates) {
         if(unrollLoop == null) {
            unrollLoop = unrollLoopCandidate;
         } else {
            if(unrollLoopCandidate.getBlocks().size() < unrollLoop.getBlocks().size()) {
               unrollLoop = unrollLoopCandidate;
            }
         }
      }
      return unrollLoop;
   }

   /**
    * Find all loops declared for unrolling. This is done by examining all conditional jumps, which hold the loop unroll declaration.
    *
    * @param program The program
    * @return All loops declared to be unrolled
    */
   private static List<NaturalLoop> findUnrollLoops(Program program) {
      NaturalLoopSet loops = program.getLoopSet();
      List<NaturalLoop> unrollLoopCandidates = new ArrayList<>();
      for(var block : program.getGraph().getAllBlocks()) {
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementConditionalJump conditionalJump) {
               Integer unrollFactor = conditionalJump.getUnrollFactor();
               if(unrollFactor != null && unrollFactor == 0) {
                  // #pragma nounroll explicitly disables unrolling for this loop
                  continue;
               }
               boolean shouldUnroll = conditionalJump.isDeclaredUnroll() || (unrollFactor != null && (unrollFactor == -1 || unrollFactor > 0));
               if(shouldUnroll) {
                  // Found a candidate for unrolling - identify the loop
                  for(NaturalLoop loopCandidate : loops.getLoopsContainingBlock(block.getLabel())) {
                     if(!loopCandidate.getBlocks().contains(block.getConditionalSuccessor())) {
                        // The conditional jump exits the loopCandidate - so we have identified the right one!
                        unrollLoopCandidates.add(loopCandidate);
                        break;
                     } else if(!loopCandidate.getBlocks().contains(block.getDefaultSuccessor())) {
                        // The default successor of the conditional exits the loopCandidate - so we have identified the right one!
                        unrollLoopCandidates.add(loopCandidate);
                        break;
                     }
                  }
               }
            }
         }
      }
      return unrollLoopCandidates;
   }

   /**
    * Find any post-peel loop tail (sentinel -2) or counted unroll loop (&gt; 0) whose loop-entry condition
    * already evaluates to false (0 remaining iterations), clearing one-shot -2 markers as they are checked.
    */
   private static NaturalLoop findAndClearZeroTripLoop(Program program) {
      NaturalLoopSet loops = program.getLoopSet();
      for(var block : program.getGraph().getAllBlocks()) {
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementConditionalJump conditionalJump) {
               Integer uf = conditionalJump.getUnrollFactor();
               if(uf != null && (uf == -2 || uf > 0)) {
                  if(uf == -2) {
                     conditionalJump.setUnrollFactor(null);
                  }
                  NaturalLoop matchingLoop = findSmallestExitedLoop(loops, block);
                  if(matchingLoop != null
                        && matchingLoop.getHead().equals(block.getLabel())
                        && isZeroTripOnEntry(block, conditionalJump, matchingLoop, program)) {
                     if(uf > 0) {
                        conditionalJump.setUnrollFactor(null);
                     }
                     return matchingLoop;
                  }
               }
            }
         }
      }
      return null;
   }

   private static NaturalLoop findSmallestExitedLoop(NaturalLoopSet loops, Graph.Block block) {
      NaturalLoop best = null;
      for(NaturalLoop loopCandidate : loops.getLoopsContainingBlock(block.getLabel())) {
         if(!loopCandidate.getBlocks().contains(block.getConditionalSuccessor())
               || !loopCandidate.getBlocks().contains(block.getDefaultSuccessor())) {
            if(best == null || loopCandidate.getBlocks().size() < best.getBlocks().size()) {
               best = loopCandidate;
            }
         }
      }
      return best;
   }

   private static void unrollHeadBlock(Graph.Block loopHeadBlock, NaturalLoop loop, Program program) {
      BlockSet unrollBlocks = () -> {
         LinkedHashSet<LabelRef> blocks = new LinkedHashSet<>();
         blocks.add(loopHeadBlock.getLabel());
         return blocks;
      };
      Unroller.UnrollStrategy unrollStrategy = new Unroller.UnrollStrategy() {
         @Override
         public TransitionHandling getEntryStrategy(LabelRef from, LabelRef to) {
            if(loop.getBlocks().contains(from)) {
               return TransitionHandling.TO_ORIGINAL;
            } else {
               return TransitionHandling.TO_COPY;
            }
         }

         @Override
         public TransitionHandling getInternalStrategy(LabelRef from, LabelRef to) {
            return TransitionHandling.TO_ORIGINAL;
         }
      };
      new Unroller(program, unrollBlocks, unrollStrategy).unroll();
   }

   private static boolean isZeroTripOnEntry(Graph.Block headBlock, StatementConditionalJump condJump, NaturalLoop loop, Program program) {
      if(headBlock.getConditionalSuccessor() == null || !headBlock.hasPhiBlock()) {
         return false;
      }
      boolean condExitsLoop = !loop.getBlocks().contains(headBlock.getConditionalSuccessor());
      boolean defExitsLoop = !loop.getBlocks().contains(headBlock.getDefaultSuccessor());
      if(condExitsLoop == defExitsLoop) {
         return false;
      }
      if(hasSideEffectsOrVolatile(headBlock, program)) {
         return false;
      }

      ProgramScope scope = program.getScope();
      Map<VariableRef, ConstantValue> entryValues = new LinkedHashMap<>();
      for(StatementPhiBlock.PhiVariable phiVar : headBlock.getPhiBlock().getPhiVariables()) {
         ConstantValue externalConst = null;
         boolean validExternal = true;
         boolean foundExternal = false;
         for(StatementPhiBlock.PhiRValue phiRVal : phiVar.getValues()) {
            if(!loop.getBlocks().contains(phiRVal.getPredecessor())) {
               foundExternal = true;
               if(phiRVal.getrValue() instanceof ConstantValue cv) {
                  try {
                     cv.calculateLiteral(scope);
                     if(externalConst == null) {
                        externalConst = cv;
                     } else if(!externalConst.equals(cv)) {
                        validExternal = false;
                        break;
                     }
                  } catch(ConstantNotLiteral e) {
                     validExternal = false;
                     break;
                  }
               } else {
                  validExternal = false;
                  break;
               }
            }
         }
         if(foundExternal && validExternal && externalConst != null) {
            entryValues.put(phiVar.getVariable(), externalConst);
         }
      }

      for(Statement stmt : headBlock.getStatements()) {
         if(stmt == condJump) {
            ConstantLiteral<?> lit = evalConditionWithMap(condJump, entryValues, scope);
            if(lit instanceof ConstantBool boolLit) {
               boolean condTrue = boolLit.getBool();
               return condExitsLoop ? condTrue : !condTrue;
            } else if(lit instanceof ConstantInteger intLit) {
               boolean condTrue = intLit.getValue() != 0;
               return condExitsLoop ? condTrue : !condTrue;
            }
            return false;
         } else if(stmt instanceof StatementAssignment assign) {
            if(!(assign.getlValue() instanceof VariableRef lhsRef) || !lhsRef.isIntermediate()) {
               return false;
            }
            ConstantValue rhsConst = evalExprWithMap(assign.getrValue1(), assign.getOperator(), assign.getrValue2(), entryValues, scope);
            if(rhsConst == null) {
               return false;
            }
            entryValues.put(lhsRef, rhsConst);
         } else if(!(stmt instanceof StatementPhiBlock)) {
            return false;
         }
      }
      return false;
   }

   private static boolean hasSideEffectsOrVolatile(Graph.Block block, Program program) {
      AtomicBoolean unsafe = new AtomicBoolean(false);
      for(Statement stmt : block.getStatements()) {
         if(stmt instanceof StatementCall) {
            return true;
         }
         ProgramValueIterator.execute(stmt, (programValue, currentStmt, stmtIt, currentBlock) -> {
            Value val = programValue.get();
            if(val instanceof PointerDereference || val instanceof StructMemberRef) {
               unsafe.set(true);
            } else if(val instanceof VariableRef vr) {
               Variable v = program.getScope().getVariable(vr);
               if(v != null && (v.isVolatile() || v.isToVolatile())) {
                  unsafe.set(true);
               }
            }
         }, null, null);
         if(unsafe.get()) {
            return true;
         }
      }
      return false;
   }

   private static ConstantValue resolveConst(RValue val, Map<VariableRef, ConstantValue> env, ProgramScope scope) {
      if(val == null) {
         return null;
      }
      if(val instanceof ConstantValue cv) {
         try {
            cv.calculateLiteral(scope);
            return cv;
         } catch(ConstantNotLiteral e) {
            return null;
         }
      }
      if(val instanceof VariableRef vr) {
         return env.get(vr);
      }
      return null;
   }

   private static ConstantValue evalExprWithMap(RValue r1, Operator op, RValue r2, Map<VariableRef, ConstantValue> env, ProgramScope scope) {
      if(op == null) {
         return resolveConst(r2, env, scope);
      }
      if(r1 == null && op instanceof OperatorUnary opUn) {
         ConstantValue c2 = resolveConst(r2, env, scope);
         if(c2 == null) {
            return null;
         }
         ConstantUnary cu = new ConstantUnary(opUn, c2);
         try {
            cu.calculateLiteral(scope);
            return cu;
         } catch(ConstantNotLiteral e) {
            return null;
         }
      }
      if(r1 != null && op instanceof OperatorBinary opBin) {
         ConstantValue c1 = resolveConst(r1, env, scope);
         ConstantValue c2 = resolveConst(r2, env, scope);
         if(c1 == null || c2 == null) {
            return null;
         }
         ConstantBinary cb = new ConstantBinary(c1, opBin, c2);
         try {
            cb.calculateLiteral(scope);
            return cb;
         } catch(ConstantNotLiteral e) {
            return null;
         }
      }
      return null;
   }

   private static ConstantLiteral<?> evalConditionWithMap(StatementConditionalJump condJump, Map<VariableRef, ConstantValue> env, ProgramScope scope) {
      ConstantValue cv = evalExprWithMap(condJump.getrValue1(), condJump.getOperator(), condJump.getrValue2(), env, scope);
      if(cv == null) {
         return null;
      }
      try {
         return cv.calculateLiteral(scope);
      } catch(ConstantNotLiteral e) {
         return null;
      }
   }

}
