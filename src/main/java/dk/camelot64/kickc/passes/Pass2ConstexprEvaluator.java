package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.CallingConventionStack;
import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.ControlFlowGraph;
import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.ProcedureCompilation;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableReferenceInfos;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.OperatorCast;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAsm;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementCall;
import dk.camelot64.kickc.model.statements.StatementCallExecute;
import dk.camelot64.kickc.model.statements.StatementConditionalJump;
import dk.camelot64.kickc.model.statements.StatementExprSideEffect;
import dk.camelot64.kickc.model.statements.StatementKickAsm;
import dk.camelot64.kickc.model.statements.StatementPhiBlock;
import dk.camelot64.kickc.model.statements.StatementReturn;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.CastValue;
import dk.camelot64.kickc.model.values.ConstantArrayFilled;
import dk.camelot64.kickc.model.values.ConstantArrayList;
import dk.camelot64.kickc.model.values.ConstantBool;
import dk.camelot64.kickc.model.values.ConstantCastValue;
import dk.camelot64.kickc.model.values.ConstantChar;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ConstantRef;
import dk.camelot64.kickc.model.values.ConstantString;
import dk.camelot64.kickc.model.values.ConstantSymbolPointer;
import dk.camelot64.kickc.model.values.ConstantValue;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.LabelRef;
import dk.camelot64.kickc.model.values.PointerDereferenceIndexed;
import dk.camelot64.kickc.model.values.PointerDereferenceSimple;
import dk.camelot64.kickc.model.values.ProcedureRef;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.StackIdxValue;
import dk.camelot64.kickc.model.values.StackPullPadding;
import dk.camelot64.kickc.model.values.StackPullValue;
import dk.camelot64.kickc.model.values.StackPushPadding;
import dk.camelot64.kickc.model.values.StackPushValue;
import dk.camelot64.kickc.model.values.SymbolRef;
import dk.camelot64.kickc.model.values.VariableRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-Time Function Evaluation (CTFE) for {@code constexpr} procedures.
 *
 * Evaluates calls to procedures marked {@link Procedure#isDeclaredConstexpr()} whose
 * arguments are statically constant, executing their CFG/SSA body in a bounded
 * side-effect-free Java VM (up to MAX_STEPS = 100_000, MAX_DEPTH = 64).
 *
 * Supports direct StatementCall (Pass 1), lowered PHI_CALL StatementCall (Pass 2),
 * and lowered STACK_CALL StatementCallExecute (Pass 2, including recursive constexpr functions),
 * replacing folded calls with constant assignments so unused constexpr procedures are
 * completely eliminated from generated 6502 assembly (0 bytes).
 */
public class Pass2ConstexprEvaluator extends Pass2SsaOptimization {

   private static final int MAX_STEPS = 100_000;
   private static final int MAX_DEPTH = 64;

   private static final class AbortCtfeException extends RuntimeException {
   }

   private static final class Frame {
      final Procedure proc;
      final Map<String, Long> env = new HashMap<>();
      final Map<String, Long> unversionedEnv = new HashMap<>();
      final byte[] stackFrame;
      final Deque<Long> hardwareStack = new ArrayDeque<>();
      Long lastPhiCallReturnVal = null;
      String lastPhiCallReturnUnversioned = null;
      String lastReturnVarFullName = null;

      Frame(Procedure proc, int stackBytes) {
         this.proc = proc;
         this.stackFrame = (stackBytes > 0) ? new byte[stackBytes] : null;
      }
   }

   private final class ConstexprVm {
      private final ProgramScope scope;
      private int stepsRemaining = MAX_STEPS;

      ConstexprVm(ProgramScope scope) {
         this.scope = scope;
      }

      long evaluate(Procedure proc, List<Long> args, int depth, LabelRef incomingCallerLabel) {
         if(depth >= MAX_DEPTH) {
            throw new AbortCtfeException();
         }
         if(!isPureConstexprCandidate(proc)) {
            throw new AbortCtfeException();
         }
         ProcedureCompilation pc = getProgram().getProcedureCompilation(proc.getRef());
         ControlFlowGraph cfg = (pc != null) ? pc.getGraph() : null;
         if(cfg == null || cfg.getAllBlocks().isEmpty()) {
            throw new AbortCtfeException();
         }

         List<Variable> paramDefs = proc.getParameters();
         if(paramDefs.size() != args.size()) {
            throw new AbortCtfeException();
         }

         int stackSize = Procedure.CallingConvention.STACK_CALL.equals(proc.getCallingConvention())
               ? (int) Math.max(1L, CallingConventionStack.getStackFrameByteSize(proc.getType()))
               : 0;
         Frame frame = new Frame(proc, stackSize);

         // Initialize parameters for direct, PHI_CALL, and STACK_CALL conventions
         for(int i = 0; i < paramDefs.size(); i++) {
            Variable param = paramDefs.get(i);
            long argVal = normalizeToType(args.get(i), param.getType());
            frame.env.put(param.getFullName(), argVal);
            frame.unversionedEnv.put(param.getFullName(), argVal);
            if(frame.stackFrame != null) {
               int offset = (int) CallingConventionStack.getParameterByteOffset(proc, param);
               int sizeBytes = param.getType().getSizeBytes();
               writeStackBytes(frame.stackFrame, offset, sizeBytes, argVal);
            }
         }

         Graph.Block curBlock = cfg.getBlock(proc.getRef().getLabelRef());
         if(curBlock == null) {
            curBlock = cfg.getAllBlocks().get(0);
         }
         LabelRef prevLabel = incomingCallerLabel;

         // Seed PHI_CALL entry block incoming parameter variables when entering from a specific caller
         if(incomingCallerLabel != null && curBlock.hasPhiBlock()) {
            for(StatementPhiBlock.PhiVariable pv : curBlock.getPhiBlock().getPhiVariables()) {
               for(StatementPhiBlock.PhiRValue prv : pv.getValues()) {
                  if(incomingCallerLabel.equals(prv.getPredecessor()) && prv.getrValue() instanceof VariableRef incomingVar) {
                     String unv = incomingVar.getFullNameUnversioned();
                     Long pVal = frame.unversionedEnv.get(unv);
                     if(pVal != null) {
                        frame.env.put(incomingVar.getFullName(), pVal);
                     }
                  }
               }
            }
         }

         Variable procRetVar = proc.getLocalVariable("return");
         String retUnversioned = (procRetVar != null) ? procRetVar.getFullName() : null;

         while(curBlock != null) {
            List<Statement> stmts = curBlock.getStatements();

            // Execute PHI block simultaneously first (if any)
            int startIdx = 0;
            if(!stmts.isEmpty() && stmts.get(0) instanceof StatementPhiBlock phiBlock) {
               startIdx = 1;
               if(!phiBlock.getPhiVariables().isEmpty()) {
                  if(prevLabel == null) {
                     throw new AbortCtfeException();
                  }
                  Map<String, Long> phiUpdates = new HashMap<>();
                  for(StatementPhiBlock.PhiVariable pv : phiBlock.getPhiVariables()) {
                     RValue incoming = pv.getrValue(prevLabel);
                     if(incoming == null) {
                        throw new AbortCtfeException();
                     }
                     long val = evalRValue(incoming, frame);
                     phiUpdates.put(pv.getVariable().getFullName(), val);
                  }
                  for(Map.Entry<String, Long> upd : phiUpdates.entrySet()) {
                     String varFullName = upd.getKey();
                     long val = upd.getValue();
                     frame.env.put(varFullName, val);
                     String unv = new VariableRef(varFullName).getFullNameUnversioned();
                     frame.unversionedEnv.put(unv, val);
                     if(unv.equals(retUnversioned)) {
                        frame.lastReturnVarFullName = varFullName;
                     }
                  }
               }
            }

            boolean jumped = false;
            for(int idx = startIdx; idx < stmts.size(); idx++) {
               if(--stepsRemaining <= 0) {
                  throw new AbortCtfeException();
               }
               Statement stmt = stmts.get(idx);

               if(stmt instanceof StatementAssignment assign) {
                  execAssignment(assign, frame, retUnversioned);
               } else if(stmt instanceof StatementCall call) {
                  execNestedCall(call, curBlock, frame);
               } else if(stmt instanceof StatementCallExecute callExec) {
                  execNestedStackCall(callExec, curBlock, frame);
               } else if(stmt instanceof StatementExprSideEffect sideEffect) {
                  RValue expr = sideEffect.getExpression();
                  if(!(expr instanceof StackPushPadding || expr instanceof StackPullPadding)) {
                     throw new AbortCtfeException();
                  }
               } else if(stmt instanceof StatementConditionalJump condJmp) {
                  if(evalCondition(condJmp, frame)) {
                     LabelRef dst = condJmp.getDestination();
                     if(dst == null) throw new AbortCtfeException();
                     prevLabel = curBlock.getLabel();
                     curBlock = cfg.getBlock(dst);
                     if(curBlock == null) throw new AbortCtfeException();
                     jumped = true;
                     break;
                  }
               } else if(stmt instanceof StatementReturn stmtReturn) {
                  return extractReturnValue(stmtReturn, proc, frame, retUnversioned);
               } else if(stmt instanceof StatementAsm || stmt instanceof StatementKickAsm) {
                  throw new AbortCtfeException();
               } else {
                  throw new AbortCtfeException();
               }
            }

            if(!jumped) {
               LabelRef nextLabel = curBlock.getDefaultSuccessor();
               if(nextLabel == null || SymbolRef.PROCEXIT_BLOCK_NAME.equals(nextLabel.getFullName())) {
                  throw new AbortCtfeException();
               }
               prevLabel = curBlock.getLabel();
               curBlock = cfg.getBlock(nextLabel);
               if(curBlock == null) {
                  throw new AbortCtfeException();
               }
            }
         }

         throw new AbortCtfeException();
      }

      private void execAssignment(StatementAssignment assign, Frame frame, String retUnversioned) {
         LValue lVal = assign.getlValue();
         long rhsVal = evalAssignmentRhs(assign, SymbolTypeInference.inferType(scope, lVal), frame);

         if(lVal instanceof VariableRef dstRef) {
            Variable dstVar = scope.getVariable(dstRef);
            if(dstVar != null) {
               boolean isLocalToFrameProc = frame.proc.equals(dstVar.getScope())
                     || frame.proc.equals(dstVar.getScope().getContainingProcedure());
               if(dstVar.isVolatile() || !isLocalToFrameProc) {
                  throw new AbortCtfeException();
               }
            }
            frame.env.put(dstRef.getFullName(), rhsVal);
            String unv = dstRef.getFullNameUnversioned();
            frame.unversionedEnv.put(unv, rhsVal);
            if(unv.equals(retUnversioned)) {
               frame.lastReturnVarFullName = dstRef.getFullName();
            }
         } else if(lVal instanceof StackPushValue) {
            frame.hardwareStack.addLast(rhsVal);
         } else if(lVal instanceof StackIdxValue stackIdx) {
            if(frame.stackFrame == null) throw new AbortCtfeException();
            int offset = (int) evalConstantVal(stackIdx.getStackOffset());
            int sizeBytes = stackIdx.getValueType().getSizeBytes();
            writeStackBytes(frame.stackFrame, offset, sizeBytes, rhsVal);
         } else {
            // PointerDereference, StructMemberRef, ValueList -> disallow side effects
            throw new AbortCtfeException();
         }
      }

      private void execNestedCall(StatementCall call, Graph.Block callerBlock, Frame frame) {
         ProcedureRef calleeRef = call.getProcedure();
         if(calleeRef == null) throw new AbortCtfeException();
         Procedure callee = scope.getProcedure(calleeRef);
         if(callee == null || !callee.isDeclaredConstexpr() || SymbolType.VOID.equals(callee.getReturnType())) {
            throw new AbortCtfeException();
         }

         List<Long> subArgs = new ArrayList<>();
         if(call.getParameters() != null) {
            for(RValue argVal : call.getParameters()) {
               subArgs.add(evalRValue(argVal, frame));
            }
         } else {
            // Lowered PHI_CALL inside constexpr procedure
            for(Variable param : callee.getParameters()) {
               Long val = frame.unversionedEnv.get(param.getFullName());
               if(val == null) throw new AbortCtfeException();
               subArgs.add(val);
            }
         }

         long subRes = evaluate(callee, subArgs, currentDepth + 1, true, callerBlock.getLabel());
         long normalized = normalizeToType(subRes, callee.getReturnType());

         if(call.getlValue() instanceof VariableRef dstRef) {
            frame.env.put(dstRef.getFullName(), normalized);
            frame.unversionedEnv.put(dstRef.getFullNameUnversioned(), normalized);
         } else if(call.getlValue() == null) {
            Variable calleeRet = callee.getLocalVariable("return");
            if(calleeRet != null) {
               frame.lastPhiCallReturnVal = normalized;
               frame.lastPhiCallReturnUnversioned = calleeRet.getFullName();
            }
         } else {
            throw new AbortCtfeException();
         }
      }

      private int currentDepth = 0;

      long evaluateRoot(Procedure proc, List<Long> args, LabelRef incomingCallerLabel) {
         return evaluate(proc, args, 0, true, incomingCallerLabel);
      }

      private long evaluate(Procedure proc, List<Long> args, int depth, boolean trackDepth, LabelRef incomingCallerLabel) {
         int prev = this.currentDepth;
         this.currentDepth = depth;
         try {
            return evaluate(proc, args, depth, incomingCallerLabel);
         } finally {
            this.currentDepth = prev;
         }
      }

      private void execNestedStackCall(StatementCallExecute callExec, Graph.Block callerBlock, Frame frame) {
         ProcedureRef calleeRef = callExec.getProcedure();
         if(calleeRef == null) throw new AbortCtfeException();
         Procedure callee = scope.getProcedure(calleeRef);
         if(callee == null || !callee.isDeclaredConstexpr() || SymbolType.VOID.equals(callee.getReturnType())) {
            throw new AbortCtfeException();
         }
         int nParams = callee.getParameters().size();
         if(frame.hardwareStack.size() < nParams) throw new AbortCtfeException();
         List<Long> subArgs = new ArrayList<>(Collections.nCopies(nParams, 0L));
         for(int i = nParams - 1; i >= 0; i--) {
            subArgs.set(i, frame.hardwareStack.removeLast());
         }
         long subRes = evaluate(callee, subArgs, currentDepth + 1, true, callerBlock.getLabel());
         frame.hardwareStack.addLast(normalizeToType(subRes, callee.getReturnType()));
      }

      private long extractReturnValue(StatementReturn stmtReturn, Procedure proc, Frame frame, String retUnversioned) {
         SymbolType retType = proc.getReturnType();
         if(SymbolType.VOID.equals(retType)) {
            throw new AbortCtfeException();
         }
         if(stmtReturn.getValue() != null) {
            return normalizeToType(evalRValue(stmtReturn.getValue(), frame), retType);
         }
         if(Procedure.CallingConvention.STACK_CALL.equals(proc.getCallingConvention()) && frame.stackFrame != null) {
            int retBytes = retType.getSizeBytes();
            int offset = (int) CallingConventionStack.getStackFrameByteSize(proc.getType()) - retBytes;
            return readStackBytes(frame.stackFrame, offset, retBytes, retType);
         }
         if(frame.lastReturnVarFullName != null && frame.env.containsKey(frame.lastReturnVarFullName)) {
            return normalizeToType(frame.env.get(frame.lastReturnVarFullName), retType);
         }
         if(retUnversioned != null && frame.unversionedEnv.containsKey(retUnversioned)) {
            return normalizeToType(frame.unversionedEnv.get(retUnversioned), retType);
         }
         throw new AbortCtfeException();
      }

      private boolean evalCondition(StatementConditionalJump jmp, Frame frame) {
         Operator op = jmp.getOperator();
         if(op == null && jmp.getrValue1() == null) {
            return evalRValue(jmp.getrValue2(), frame) != 0L;
         }
         if(jmp.getrValue1() != null && jmp.getrValue2() != null) {
            long a = evalRValue(jmp.getrValue1(), frame);
            long b = evalRValue(jmp.getrValue2(), frame);
            if(Operators.EQ.equals(op)) return a == b;
            if(Operators.NEQ.equals(op)) return a != b;
            if(Operators.LT.equals(op)) return a < b;
            if(Operators.LE.equals(op)) return a <= b;
            if(Operators.GT.equals(op)) return a > b;
            if(Operators.GE.equals(op)) return a >= b;
         }
         throw new AbortCtfeException();
      }

      private long evalAssignmentRhs(StatementAssignment assign, SymbolType dstType, Frame frame) {
         Operator op = assign.getOperator();
         if(op == null) {
            return normalizeToType(evalRValue(assign.getrValue2(), frame), dstType);
         }
         if(assign.getrValue1() == null) {
            long val = evalRValue(assign.getrValue2(), frame);
            long raw;
            if(op instanceof OperatorCast castOp) {
               raw = normalizeToType(val, castOp.getToType());
            } else if(Operators.POS.equals(op)) {
               raw = val;
            } else if(Operators.NEG.equals(op)) {
               raw = -val;
            } else if(Operators.BOOL_NOT.equals(op)) {
               raw = ~val;
            } else if(Operators.LOGIC_NOT.equals(op)) {
               raw = (val == 0L) ? 1L : 0L;
            } else if(Operators.INCREMENT.equals(op)) {
               raw = val + 1L;
            } else if(Operators.DECREMENT.equals(op)) {
               raw = val - 1L;
            } else if(Operators.BYTE0.equals(op)) {
               raw = val & 0xFFL;
            } else if(Operators.BYTE1.equals(op)) {
               raw = (val >>> 8) & 0xFFL;
            } else if(Operators.BYTE2.equals(op)) {
               raw = (val >>> 16) & 0xFFL;
            } else if(Operators.BYTE3.equals(op)) {
               raw = (val >>> 24) & 0xFFL;
            } else if(Operators.WORD0.equals(op)) {
               raw = val & 0xFFFFL;
            } else if(Operators.WORD1.equals(op)) {
               raw = (val >>> 16) & 0xFFFFL;
            } else {
               throw new AbortCtfeException();
            }
            return normalizeToType(raw, dstType);
         }

         long a = evalRValue(assign.getrValue1(), frame);
         long b = evalRValue(assign.getrValue2(), frame);
         long raw;
         if(Operators.PLUS.equals(op)) {
            raw = a + b;
         } else if(Operators.MINUS.equals(op)) {
            raw = a - b;
         } else if(Operators.MULTIPLY.equals(op)) {
            raw = a * b;
         } else if(Operators.DIVIDE.equals(op)) {
            if(b == 0L) throw new AbortCtfeException();
            raw = a / b;
         } else if(Operators.MODULO.equals(op)) {
            if(b == 0L) throw new AbortCtfeException();
            raw = a % b;
         } else if(Operators.BOOL_AND.equals(op)) {
            raw = a & b;
         } else if(Operators.BOOL_OR.equals(op)) {
            raw = a | b;
         } else if(Operators.BOOL_XOR.equals(op)) {
            raw = a ^ b;
         } else if(Operators.LOGIC_AND.equals(op)) {
            raw = (a != 0L && b != 0L) ? 1L : 0L;
         } else if(Operators.LOGIC_OR.equals(op)) {
            raw = (a != 0L || b != 0L) ? 1L : 0L;
         } else if(Operators.SHIFT_LEFT.equals(op)) {
            raw = (b < 0L || b >= 64L) ? 0L : (a << (int) b);
         } else if(Operators.SHIFT_RIGHT.equals(op)) {
            if(b < 0L || b >= 64L) {
               raw = 0L;
            } else {
               SymbolType lhsType = SymbolTypeInference.inferType(scope, assign.getrValue1());
               if(lhsType instanceof SymbolTypeIntegerFixed fixed && !fixed.isSigned()) {
                  long mask = (fixed.getBits() >= 64) ? -1L : ((1L << fixed.getBits()) - 1L);
                  raw = (a & mask) >>> (int) b;
               } else {
                  raw = a >> (int) b;
               }
            }
         } else if(Operators.EQ.equals(op)) {
            raw = (a == b) ? 1L : 0L;
         } else if(Operators.NEQ.equals(op)) {
            raw = (a != b) ? 1L : 0L;
         } else if(Operators.LT.equals(op)) {
            raw = (a < b) ? 1L : 0L;
         } else if(Operators.LE.equals(op)) {
            raw = (a <= b) ? 1L : 0L;
         } else if(Operators.GT.equals(op)) {
            raw = (a > b) ? 1L : 0L;
         } else if(Operators.GE.equals(op)) {
            raw = (a >= b) ? 1L : 0L;
         } else if(Operators.WORD.equals(op)) {
            raw = ((a & 0xFFL) << 8) | (b & 0xFFL);
         } else if(Operators.DWORD.equals(op)) {
            raw = ((a & 0xFFFFL) << 16) | (b & 0xFFFFL);
         } else {
            throw new AbortCtfeException();
         }
         return normalizeToType(raw, dstType);
      }

      private long evalRValue(RValue val, Frame frame) {
         if(val == null) throw new AbortCtfeException();
         if(val instanceof ConstantValue cv) {
            return evalConstantVal(cv);
         }
         if(val instanceof CastValue cast) {
            long inner = evalRValue(cast.getValue(), frame);
            return normalizeToType(inner, cast.getToType());
         }
         if(val instanceof StackPullValue) {
            if(frame.hardwareStack.isEmpty()) throw new AbortCtfeException();
            return frame.hardwareStack.removeLast();
         }
         if(val instanceof StackIdxValue stackIdx) {
            if(frame.stackFrame == null) throw new AbortCtfeException();
            int offset = (int) evalConstantVal(stackIdx.getStackOffset());
            int sizeBytes = stackIdx.getValueType().getSizeBytes();
            return readStackBytes(frame.stackFrame, offset, sizeBytes, stackIdx.getValueType());
         }
         if(val instanceof VariableRef vr) {
            Long v = frame.env.get(vr.getFullName());
            if(v != null) return v;
            if(frame.lastPhiCallReturnUnversioned != null && vr.getFullNameUnversioned().equals(frame.lastPhiCallReturnUnversioned)) {
               return frame.lastPhiCallReturnVal;
            }
            // Fallback for single-versioned parameter variable without entry PHI
            String unv = vr.getFullNameUnversioned();
            for(Variable param : frame.proc.getParameters()) {
               if(param.getFullName().equals(unv) && frame.unversionedEnv.containsKey(unv)) {
                  return frame.unversionedEnv.get(unv);
               }
            }
            // Check if vr refers to a global scalar constant
            Variable sym = scope.getVariable(vr);
            if(sym != null && sym.isKindConstant() && sym.getInitValue() != null) {
               return evalConstantVal(sym.getInitValue());
            }
            throw new AbortCtfeException();
         }
         if(val instanceof PointerDereferenceIndexed derefIdx) {
            long index = evalRValue(derefIdx.getIndex(), frame);
            return readConstArrayElement(derefIdx.getPointer(), index);
         }
         if(val instanceof PointerDereferenceSimple derefSimple) {
            return readConstArrayElement(derefSimple.getPointer(), 0L);
         }
         throw new AbortCtfeException();
      }

      private long readConstArrayElement(RValue ptr, long index) {
         ConstantRef arrRef = extractConstantArrayRef(ptr);
         if(arrRef == null) throw new AbortCtfeException();
         Variable arrVar = scope.getConstant(arrRef);
         if(arrVar == null || arrVar.getInitValue() == null) throw new AbortCtfeException();
         ConstantValue init = arrVar.getInitValue();
         if(init instanceof ConstantArrayList cal) {
            List<ConstantValue> elems = cal.getElements();
            if(index < 0L || index >= elems.size()) throw new AbortCtfeException();
            return evalConstantVal(elems.get((int) index));
         }
         if(init instanceof ConstantArrayFilled caf) {
            long sz = evalConstantVal(caf.getSize());
            if(index < 0L || index >= sz) throw new AbortCtfeException();
            return 0L;
         }
         if(init instanceof ConstantString cs) {
            String s = cs.getValue();
            int len = s.length() + (cs.isZeroTerminated() ? 1 : 0);
            if(index < 0L || index >= len) throw new AbortCtfeException();
            if(index == s.length()) return 0L;
            return cs.getEncoding().encodedFromChar(s.charAt((int) index)) & 0xFFL;
         }
         throw new AbortCtfeException();
      }

      private ConstantRef extractConstantArrayRef(RValue ptr) {
         if(ptr instanceof ConstantRef cr) return cr;
         if(ptr instanceof ConstantCastValue ccv) return extractConstantArrayRef(ccv.getValue());
         if(ptr instanceof CastValue cv) return extractConstantArrayRef(cv.getValue());
         if(ptr instanceof ConstantSymbolPointer csp && csp.getToSymbol() instanceof ConstantRef cr) return cr;
         return null;
      }

      private long evalConstantVal(ConstantValue cv) {
         if(cv instanceof ConstantInteger ci) {
            return ci.getInteger();
         }
         if(cv instanceof ConstantBool cb) {
            return cb.getBool() ? 1L : 0L;
         }
         if(cv instanceof ConstantChar cc) {
            return cc.getInteger();
         }
         if(cv instanceof ConstantCastValue ccv) {
            long inner = evalConstantVal(ccv.getValue());
            return normalizeToType(inner, ccv.getToType());
         }
         try {
            ConstantLiteral lit = cv.calculateLiteral(scope);
            if(lit instanceof ConstantInteger ci) return ci.getInteger();
            if(lit instanceof ConstantBool cb) return cb.getBool() ? 1L : 0L;
            if(lit instanceof ConstantChar cc) return cc.getInteger();
         } catch(ConstantNotLiteral ignored) {
         }
         throw new AbortCtfeException();
      }
   }

   public Pass2ConstexprEvaluator(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      ProgramScope scope = getProgramScope();
      if(!hasAnyConstexprProcedure(scope)) {
         return false;
      }

      VariableReferenceInfos refInfos = hasStatementIndices() ? getProgram().getVariableReferenceInfos() : null;
      boolean modified = false;

      for(ProcedureCompilation pc : getProgram().getProcedureCompilations()) {
         ControlFlowGraph cfg = pc.getGraph();
         if(cfg == null) continue;
         for(Graph.Block block : new ArrayList<>(cfg.getAllBlocks())) {
            List<Statement> stmts = block.getStatements();
            for(int idx = 0; idx < stmts.size(); idx++) {
               Statement stmt = stmts.get(idx);
               if(stmt instanceof StatementCall call) {
                  if(tryFoldStatementCall(call, block, idx, scope, refInfos)) {
                     modified = true;
                     break;
                  }
               } else if(stmt instanceof StatementCallExecute callExec) {
                  if(tryFoldStackCallExecute(callExec, block, idx, scope, refInfos)) {
                     modified = true;
                     break;
                  }
               }
            }
            if(modified) {
               getProgram().clearVariableReferenceInfos();
               getProgram().clearStatementInfos();
               getProgram().clearControlFlowBlockSuccessorClosure();
               return true;
            }
         }
      }

      return false;
   }

   private boolean tryFoldStatementCall(
         StatementCall call,
         Graph.Block block,
         int callIdx,
         ProgramScope scope,
         VariableReferenceInfos refInfos) {
      ProcedureRef procRef = call.getProcedure();
      if(procRef == null) return false;
      Procedure proc = scope.getProcedure(procRef);
      if(!isPureConstexprCandidate(proc)) return false;

      List<Long> args = new ArrayList<>();
      if(call.getParameters() != null) {
         // Direct (unlowered) StatementCall
         for(RValue argVal : call.getParameters()) {
            Long c = resolveCallerConstant(argVal, block, callIdx, scope, refInfos, 0);
            if(c == null) return false;
            args.add(c);
         }
         Long res = runVm(proc, args, null, scope);
         if(res == null) return false;
         if(call.getlValue() == null) return false;

         ConstantValue constVal = makeConstantValue(res, proc.getReturnType());
         StatementAssignment constAssign = new StatementAssignment(
               call.getlValue(), null, null, constVal,
               call.isInitialAssignment(), call.getSource(), call.getComments());
         block.getStatements().set(callIdx, constAssign);
         block.setCallSuccessor(null);
         getLog().append("CTFE evaluated constexpr " + proc.getFullName() + args + " -> " + constVal.toString(getProgram()));
         return true;
      }

      // Lowered PHI_CALL StatementCall (Pass 2)
      if(!Procedure.CallingConvention.PHI_CALL.equals(proc.getCallingConvention())) {
         return false;
      }
      List<Variable> params = proc.getParameters();
      for(Variable param : params) {
         Long c = resolvePhiCallParamConstant(param, proc, block, callIdx, scope, refInfos);
         if(c == null) return false;
         args.add(c);
      }

      Long res = runVm(proc, args, block.getLabel(), scope);
      if(res == null) return false;

      // Locate return assignment in block.getDefaultSuccessor()
      Graph.Block nextBlock = (block.getDefaultSuccessor() != null) ? getGraph().getBlock(block.getDefaultSuccessor()) : null;
      Variable procRetVar = proc.getLocalVariable("return");
      if(nextBlock != null && procRetVar != null) {
         String retUnversioned = procRetVar.getFullName();
         ConstantValue constVal = makeConstantValue(res, proc.getReturnType());
         for(Statement nextStmt : nextBlock.getStatements()) {
            if(nextStmt instanceof StatementAssignment retAssign
                  && retAssign.getOperator() == null
                  && retAssign.getrValue1() == null
                  && retAssign.getrValue2() instanceof VariableRef vr
                  && vr.getFullNameUnversioned().equals(retUnversioned)) {
               retAssign.setrValue2(constVal);
            }
         }
      }

      // Remove call and clean PHI predecessor entry on callee entry block if present
      block.getStatements().remove(callIdx);
      block.setCallSuccessor(null);
      ProcedureCompilation calleePc = getProgram().getProcedureCompilation(proc.getRef());
      if(calleePc != null && calleePc.getGraph() != null) {
         Graph.Block calleeEntry = calleePc.getGraph().getBlock(proc.getRef().getLabelRef());
         if(calleeEntry != null) {
            Pass2EliminateUnusedBlocks.removePhiRValues(block.getLabel(), calleeEntry, getLog());
         }
      }
      getLog().append("CTFE evaluated constexpr PHI_CALL " + proc.getFullName() + args + " -> " + res);
      return true;
   }

   private boolean tryFoldStackCallExecute(
         StatementCallExecute callExec,
         Graph.Block block,
         int execIdx,
         ProgramScope scope,
         VariableReferenceInfos refInfos) {
      ProcedureRef procRef = callExec.getProcedure();
      if(procRef == null) return false;
      Procedure proc = scope.getProcedure(procRef);
      if(!isPureConstexprCandidate(proc)) return false;
      if(!Procedure.CallingConvention.STACK_CALL.equals(proc.getCallingConvention())) return false;

      List<Statement> stmts = block.getStatements();
      int nParams = proc.getParameters().size();

      // Match preceding StackPushPadding (optional) and nParams StackPushValue assignments
      int scanIdx = execIdx - 1;
      int pushPadIdx = -1;
      if(scanIdx >= 0 && stmts.get(scanIdx) instanceof StatementExprSideEffect se
            && se.getExpression() instanceof StackPushPadding) {
         pushPadIdx = scanIdx;
         scanIdx--;
      }

      List<Integer> pushIndices = new ArrayList<>(nParams);
      List<Long> revArgs = new ArrayList<>(nParams);
      for(int i = 0; i < nParams; i++) {
         if(scanIdx < 0) return false;
         if(!(stmts.get(scanIdx) instanceof StatementAssignment pushAssign)) return false;
         if(!(pushAssign.getlValue() instanceof StackPushValue)) return false;
         if(pushAssign.getOperator() != null || pushAssign.getrValue1() != null) return false;
         Long argVal = resolveCallerConstant(pushAssign.getrValue2(), block, scanIdx, scope, refInfos, 0);
         if(argVal == null) return false;
         pushIndices.add(scanIdx);
         revArgs.add(argVal);
         scanIdx--;
      }
      List<Long> args = new ArrayList<>(revArgs);
      Collections.reverse(args);

      // Match following StackPullPadding (optional) and dst = StackPullValue
      int afterIdx = execIdx + 1;
      int pullPadIdx = -1;
      if(afterIdx < stmts.size() && stmts.get(afterIdx) instanceof StatementExprSideEffect se
            && se.getExpression() instanceof StackPullPadding) {
         pullPadIdx = afterIdx;
         afterIdx++;
      }
      if(afterIdx >= stmts.size()) return false;
      if(!(stmts.get(afterIdx) instanceof StatementAssignment pullAssign)) return false;
      if(pullAssign.getOperator() != null || pullAssign.getrValue1() != null) return false;
      if(!(pullAssign.getrValue2() instanceof StackPullValue)) return false;

      Long res = runVm(proc, args, block.getLabel(), scope);
      if(res == null) return false;

      ConstantValue constVal = makeConstantValue(res, proc.getReturnType());
      pullAssign.setrValue2(constVal);

      // Remove consumed stack-call statements in descending index order
      List<Integer> toRemove = new ArrayList<>();
      if(pullPadIdx >= 0) toRemove.add(pullPadIdx);
      toRemove.add(execIdx);
      if(pushPadIdx >= 0) toRemove.add(pushPadIdx);
      toRemove.addAll(pushIndices);
      toRemove.sort(Collections.reverseOrder());
      for(int rmIdx : toRemove) {
         stmts.remove(rmIdx);
      }
      block.setCallSuccessor(null);
      getLog().append("CTFE evaluated constexpr STACK_CALL " + proc.getFullName() + args + " -> " + constVal.toString(getProgram()));
      return true;
   }

   private Long runVm(Procedure proc, List<Long> args, LabelRef callerLabel, ProgramScope scope) {
      try {
         return new ConstexprVm(scope).evaluateRoot(proc, args, callerLabel);
      } catch(AbortCtfeException ignored) {
         return null;
      }
   }

   private boolean hasStatementIndices() {
      for(Graph.Block block : getGraph().getAllBlocks()) {
         for(Statement stmt : block.getStatements()) {
            return stmt.getIndex() != null;
         }
      }
      return false;
   }

   private Long resolvePhiCallParamConstant(
         Variable param,
         Procedure proc,
         Graph.Block block,
         int callIdx,
         ProgramScope scope,
         VariableReferenceInfos refInfos) {
      String paramUnversioned = param.getFullName();
      List<Statement> stmts = block.getStatements();
      for(int i = callIdx - 1; i >= 0; i--) {
         if(stmts.get(i) instanceof StatementAssignment assign
               && assign.getOperator() == null
               && assign.getrValue1() == null
               && assign.getlValue() instanceof VariableRef lv
               && lv.getFullNameUnversioned().equals(paramUnversioned)) {
            return resolveCallerConstant(assign.getrValue2(), block, i, scope, refInfos, 0);
         }
      }
      // Check callee's entry PHI block in case Pass2ConstantValues already inlined the constant into the PHI
      ProcedureCompilation calleePc = getProgram().getProcedureCompilation(proc.getRef());
      if(calleePc != null && calleePc.getGraph() != null) {
         Graph.Block calleeEntry = calleePc.getGraph().getBlock(proc.getRef().getLabelRef());
         if(calleeEntry != null && calleeEntry.hasPhiBlock()) {
            for(StatementPhiBlock.PhiVariable pv : calleeEntry.getPhiBlock().getPhiVariables()) {
               if(pv.getVariable().getFullNameUnversioned().equals(paramUnversioned)) {
                  RValue incoming = pv.getrValue(block.getLabel());
                  if(incoming != null) {
                     Long c = resolveCallerConstant(incoming, block, callIdx, scope, refInfos, 0);
                     if(c != null) return c;
                  }
               }
            }
         }
      }
      return null;
   }

   private Long resolveCallerConstant(
         RValue val,
         Graph.Block block,
         int beforeIdx,
         ProgramScope scope,
         VariableReferenceInfos refInfos,
         int depth) {
      if(depth > 8 || val == null) return null;
      if(val instanceof ConstantValue cv) {
         try {
            ConstantLiteral lit = cv.calculateLiteral(scope);
            if(lit instanceof ConstantInteger ci) return ci.getInteger();
            if(lit instanceof ConstantBool cb) return cb.getBool() ? 1L : 0L;
            if(lit instanceof ConstantChar cc) return cc.getInteger();
         } catch(ConstantNotLiteral ignored) {
         }
         return null;
      }
      if(val instanceof CastValue cast) {
         Long inner = resolveCallerConstant(cast.getValue(), block, beforeIdx, scope, refInfos, depth + 1);
         return (inner != null) ? normalizeToType(inner, cast.getToType()) : null;
      }
      if(val instanceof VariableRef vr) {
         List<Statement> stmts = block.getStatements();
         for(int i = beforeIdx - 1; i >= 0; i--) {
            if(stmts.get(i) instanceof StatementAssignment assign && vr.equals(assign.getlValue())) {
               if(assign.getOperator() == null && assign.getrValue1() == null) {
                  return resolveCallerConstant(assign.getrValue2(), block, i, scope, refInfos, depth + 1);
               }
               return null;
            }
         }
         if(refInfos != null) {
            Integer defIdx = refInfos.getVarDefineStatement(vr);
            if(defIdx != null) {
               Statement def = getGraph().getStatementByIndex(defIdx);
               if(def instanceof StatementAssignment assign
                     && assign.getOperator() == null
                     && assign.getrValue1() == null
                     && vr.equals(assign.getlValue())) {
                  return resolveCallerConstant(assign.getrValue2(), block, 0, scope, refInfos, depth + 1);
               }
            }
         }
      }
      return null;
   }

   private static boolean hasAnyConstexprProcedure(ProgramScope scope) {
      for(Procedure p : scope.getAllProcedures(true)) {
         if(p.isDeclaredConstexpr()) return true;
      }
      return false;
   }

   private boolean isPureConstexprCandidate(Procedure proc) {
      if(proc == null || !proc.isDeclaredConstexpr()) return false;
      if(proc.isDeclaredIntrinsic() || proc.getInterruptType() != null || proc.getFixedAddress() != null) return false;
      SymbolType retType = proc.getReturnType();
      if(retType == null || SymbolType.VOID.equals(retType)) return false;
      if(!(retType instanceof SymbolTypeIntegerFixed) && !SymbolType.BOOLEAN.equals(retType)) return false;
      ProcedureCompilation pc = getProgram().getProcedureCompilation(proc.getRef());
      return pc != null && pc.getGraph() != null && !pc.getGraph().getAllBlocks().isEmpty();
   }

   private static long normalizeToType(long value, SymbolType type) {
      if(type instanceof SymbolTypeIntegerFixed fixed) {
         return fixed.normalize(value);
      }
      if(SymbolType.BOOLEAN.equals(type)) {
         return (value != 0L) ? 1L : 0L;
      }
      return value;
   }

   private static ConstantValue makeConstantValue(long value, SymbolType type) {
      if(SymbolType.BOOLEAN.equals(type)) {
         return new ConstantBool(value != 0L);
      }
      if(type instanceof SymbolTypeIntegerFixed fixed) {
         return new ConstantInteger(fixed.normalize(value), fixed);
      }
      return new ConstantInteger(value);
   }

   private static void writeStackBytes(byte[] stack, int offset, int sizeBytes, long value) {
      if(offset < 0 || offset + sizeBytes > stack.length) {
         throw new AbortCtfeException();
      }
      for(int b = 0; b < sizeBytes; b++) {
         stack[offset + b] = (byte) ((value >>> (8 * b)) & 0xFFL);
      }
   }

   private static long readStackBytes(byte[] stack, int offset, int sizeBytes, SymbolType type) {
      if(offset < 0 || offset + sizeBytes > stack.length) {
         throw new AbortCtfeException();
      }
      long res = 0L;
      for(int b = 0; b < sizeBytes; b++) {
         res |= (stack[offset + b] & 0xFFL) << (8 * b);
      }
      return normalizeToType(res, type);
   }
}
