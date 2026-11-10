package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.Bank;
import dk.camelot64.kickc.model.types.SymbolTypeProcedure;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementCall;
import dk.camelot64.kickc.model.statements.StatementReturn;
import dk.camelot64.kickc.model.symbols.Label;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;

import java.util.*;

/**
 * Pass 1: Adaptive Convenience-C Arithmetic Synthesizer & Code Size Guardian.
 * Evaluates CPU cycles and code footprint for non-power-of-two divisions, multiplications, and modulo.
 * Automatically chooses between direct inlining, synthesizing dedicated ad-hoc helper subroutines,
 * and standard library calls based on cycle speed advantages and occurrence counts.
 * Fully supports signed/unsigned 8-bit and 16-bit arithmetic (int8/int8, uint8/uint8, int16/int16, uint16/uint16).
 */
public class Pass1ConvenienceMathRewrite extends Pass2SsaOptimization {

   public Pass1ConvenienceMathRewrite(Program program) {
      super(program);
   }

   private List<RValue> list(RValue... items) {
      return new ArrayList<>(Arrays.asList(items));
   }

   private boolean isSigned(SymbolType type) {
      if(type instanceof SymbolTypeIntegerFixed fixed) {
         return fixed.isSigned();
      }
      return false;
   }

   private boolean isLibraryMathProcedure(String scope) {
      if(scope.startsWith("::")) scope = scope.substring(2);
      if(scope.startsWith("__div16u_const_") || scope.startsWith("__div8u_const_")
            || scope.startsWith("__mod16u_const_") || scope.startsWith("__mod8u_const_")
            || scope.startsWith("__mul16u_const_") || scope.startsWith("__mul8u_const_")) {
         return true;
      }
      return scope.equals("div8u") || scope.equals("divr8u") || scope.equals("div16u")
            || scope.equals("div16u8u") || scope.equals("div8s") || scope.equals("div16s")
            || scope.equals("div10u") || scope.equals("mod10u")
            || scope.equals("mod8u") || scope.equals("mod8s") || scope.equals("mod16u") || scope.equals("mod16s")
            || scope.equals("modr16u") || scope.equals("div32u16u") || scope.equals("div32u") || scope.equals("mod32u") || scope.equals("div32s") || scope.equals("mod32s") || scope.equals("div24u") || scope.equals("mod24u") || scope.equals("div24s") || scope.equals("mod24s")
            || scope.equals("mul8u") || scope.equals("mul8s") || scope.equals("mul8su")
            || scope.equals("mul16u") || scope.equals("mul16s");
   }

   private Procedure getOrCreateHelperProcedure16uDiv(long constant) {
      String procName = "__div16u_const_" + constant;
      Procedure existing = getProgramScope().getLocalProcedure(procName);
      if(existing != null) return existing;

      Procedure procedure = new Procedure(procName,
            new SymbolTypeProcedure(SymbolType.WORD, Collections.singletonList(SymbolType.WORD)),
            getProgramScope(), Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT,
            Procedure.CallingConvention.PHI_CALL, Bank.COMMON);

      Variable param = new Variable("val", Variable.Kind.PHI_MASTER, SymbolType.WORD, procedure,
            Variable.MemoryArea.MAIN_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
      procedure.add(param);
      procedure.setParameters(Collections.singletonList(param));

      Variable returnVar = new Variable("return", Variable.Kind.PHI_MASTER, SymbolType.WORD, procedure,
            Variable.MemoryArea.MAIN_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
      procedure.add(returnVar);

      getProgramScope().add(procedure);

      ProcedureCompilation pc = getProgram().createProcedureCompilation(procedure.getRef());

      Label procExit = procedure.addLabel(SymbolRef.PROCEXIT_BLOCK_NAME);
      Graph.Block procBlock = new ControlFlowBlock(procedure.getLabel().getRef(), procedure.getRef());

      StatementAssignment assign = new StatementAssignment(
            (LValue) returnVar.getRef(),
            param.getRef(),
            Operators.DIVIDE,
            new ConstantInteger(constant, SymbolType.WORD),
            true, StatementSource.NONE, Comment.NO_COMMENTS);
      procBlock.addStatement(assign);
      procBlock.setDefaultSuccessor(procExit.getRef());

      Graph.Block returnBlock = new ControlFlowBlock(procExit.getRef(), procedure.getRef());
      returnBlock.addStatement(new StatementAssignment(returnVar.getVariableRef(), returnVar.getRef(), false, StatementSource.NONE, Comment.NO_COMMENTS));
      returnBlock.addStatement(new StatementReturn(returnVar.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));

      List<Graph.Block> blocks = new ArrayList<>();
      blocks.add(procBlock);
      blocks.add(returnBlock);

      ControlFlowGraph cfg = new ControlFlowGraph(blocks);
      pc.setGraph(cfg);

      return procedure;
   }

   private Procedure getOrCreateHelperProcedure8uDiv(long constant) {
      String procName = "__div8u_const_" + constant;
      Procedure existing = getProgramScope().getLocalProcedure(procName);
      if(existing != null) return existing;

      Procedure procedure = new Procedure(procName,
            new SymbolTypeProcedure(SymbolType.BYTE, Collections.singletonList(SymbolType.BYTE)),
            getProgramScope(), Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT,
            Procedure.CallingConvention.PHI_CALL, Bank.COMMON);

      Variable param = new Variable("val", Variable.Kind.PHI_MASTER, SymbolType.BYTE, procedure,
            Variable.MemoryArea.MAIN_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
      procedure.add(param);
      procedure.setParameters(Collections.singletonList(param));

      Variable returnVar = new Variable("return", Variable.Kind.PHI_MASTER, SymbolType.BYTE, procedure,
            Variable.MemoryArea.MAIN_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
      procedure.add(returnVar);

      getProgramScope().add(procedure);

      ProcedureCompilation pc = getProgram().createProcedureCompilation(procedure.getRef());

      Label procExit = procedure.addLabel(SymbolRef.PROCEXIT_BLOCK_NAME);
      Graph.Block procBlock = new ControlFlowBlock(procedure.getLabel().getRef(), procedure.getRef());

      StatementAssignment assign = new StatementAssignment(
            (LValue) returnVar.getRef(),
            param.getRef(),
            Operators.DIVIDE,
            new ConstantInteger(constant, SymbolType.BYTE),
            true, StatementSource.NONE, Comment.NO_COMMENTS);
      procBlock.addStatement(assign);
      procBlock.setDefaultSuccessor(procExit.getRef());

      Graph.Block returnBlock = new ControlFlowBlock(procExit.getRef(), procedure.getRef());
      returnBlock.addStatement(new StatementAssignment(returnVar.getVariableRef(), returnVar.getRef(), false, StatementSource.NONE, Comment.NO_COMMENTS));
      returnBlock.addStatement(new StatementReturn(returnVar.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));

      List<Graph.Block> blocks = new ArrayList<>();
      blocks.add(procBlock);
      blocks.add(returnBlock);

      ControlFlowGraph cfg = new ControlFlowGraph(blocks);
      pc.setGraph(cfg);

      return procedure;
   }

   @Override
   public boolean step() {
      if(!getProgram().isEnableConvenience()) {
         return false;
      }

      // Step 1: Whole-program frequency counting of constant operations
      Map<Long, Integer> divConstCounts = new HashMap<>();
      Map<Long, Integer> mulConstCounts = new HashMap<>();

      for(var block : getGraph().getAllBlocks()) {
         String scopeStr = block.getScope() != null ? block.getScope().toString() : "";
         if(isLibraryMathProcedure(scopeStr)) {
            continue;
         }
         for(Statement statement : block.getStatements()) {
            if(statement instanceof StatementAssignment assign) {
               Operator op = assign.getOperator();
               if(Operators.DIVIDE.equals(op) && assign.getrValue2() instanceof ConstantInteger constInt) {
                  long c = constInt.getInteger();
                  divConstCounts.put(c, divConstCounts.getOrDefault(c, 0) + 1);
               } else if(Operators.MODULO.equals(op) && assign.getrValue2() instanceof ConstantInteger constInt) {
                  long c = constInt.getInteger();
                  divConstCounts.put(c, divConstCounts.getOrDefault(c, 0) + 1);
               } else if(Operators.MULTIPLY.equals(op) && assign.getrValue2() instanceof ConstantInteger constInt) {
                  long c = constInt.getInteger();
                  mulConstCounts.put(c, mulConstCounts.getOrDefault(c, 0) + 1);
               }
            }
         }
      }

      // Step 2: Cycle- and code-size-driven rewriting
      boolean modified = false;
      for(var block : getGraph().getAllBlocks()) {
         String scopeStr = block.getScope() != null ? block.getScope().toString() : "";
         if(isLibraryMathProcedure(scopeStr)) {
            continue;
         }
         final ListIterator<Statement> stmtIt = block.getStatements().listIterator();
         while(stmtIt.hasNext()) {
            Statement statement = stmtIt.next();
            if(statement instanceof StatementAssignment assign) {
               Operator op = assign.getOperator();
               if(Operators.DIVIDE.equals(op)) {
                  modified |= rewriteDivide(assign, stmtIt, divConstCounts);
               } else if(Operators.MODULO.equals(op)) {
                  modified |= rewriteModulo(assign, stmtIt, divConstCounts);
               } else if(Operators.MULTIPLY.equals(op)) {
                  modified |= rewriteMultiply(assign, stmtIt, mulConstCounts);
               }
            }
         }
      }
      return modified;
   }

   private boolean rewriteDivide(StatementAssignment assign, ListIterator<Statement> stmtIt, Map<Long, Integer> divConstCounts) {
      RValue dividend = assign.getrValue1();
      RValue divisor = assign.getrValue2();
      SymbolType divType = SymbolTypeInference.inferType(getProgramScope(), dividend);
      SymbolType divisorType = SymbolTypeInference.inferType(getProgramScope(), divisor);
      boolean isSigned = isSigned(divType) || isSigned(divisorType);
      int divBytes = divType.getSizeBytes();
      int divisorBytes = divisorType.getSizeBytes();

      if(divisor instanceof ConstantInteger constInt) {
         long c = constInt.getInteger();
         if(c == 1) {
            return false; // Identity, handled in Pass 2
         }
         double pow2 = Math.log(c) / Math.log(2);
         if(pow2 >= 0.0 && Math.round(pow2) == pow2) {
            if(!isSigned) {
               return false; // Power of 2 unsigned, optimal shift in Pass 2
            }
            if(divBytes == 1) {
               Procedure div8s = getProgramScope().getLocalProcedure("div8s");
               if(div8s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div8s",
                        list(dividend, new ConstantInteger(c, SymbolType.SBYTE)),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(div8s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               Procedure div16s = getProgramScope().getLocalProcedure("div16s");
               if(div16s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div16s",
                        list(dividend, new ConstantInteger(c, SymbolType.SWORD)),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(div16s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         }

         int occurrences = divConstCounts.getOrDefault(c, 0);

         // 8-bit signed constant division (int8 / const)
         if(isSigned && divBytes == 1) {
            Procedure div8s = getProgramScope().getLocalProcedure("div8s");
            if(div8s != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "div8s",
                     list(dividend, new ConstantInteger(c, SymbolType.SBYTE)),
                     assign.getSource(), assign.getComments());
               call.setProcedure(div8s.getRef());
               stmtIt.set(call);
               return true;
            }
            return false;
         }

         // 8-bit unsigned constant division (uint8 / const)
         if(!isSigned && divBytes == 1 && c <= 255) {
            Pass2MultiplyToShiftRewriting.Reciprocal recip = Pass2MultiplyToShiftRewriting.findReciprocal(c);
            if(recip != null) {
               // Fast reciprocal multiplication is ~22 cycles (10x faster than div8u's ~250 cycles!)
               if(occurrences >= 4) {
                  // Synthesize dedicated ad-hoc helper subroutine to avoid duplicating reciprocal code
                  Procedure helper = getOrCreateHelperProcedure8uDiv(c);
                  StatementCall call = new StatementCall(assign.getlValue(), helper.getLocalName(),
                        list(dividend), assign.getSource(), assign.getComments());
                  call.setProcedure(helper.getRef());
                  stmtIt.set(call);
                  return true;
               } else {
                  // Occurs <= 3 times: direct inlining is optimal
                  return false;
               }
            } else {
               // No exact reciprocal: redirect to library function div8u
               Procedure div8u = getProgramScope().getLocalProcedure("div8u");
               if(div8u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div8u",
                        list(dividend, new ConstantInteger(c, SymbolType.BYTE)),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(div8u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
            return false;
         }

         // 16-bit signed constant division (int16 / const)
         if(isSigned && divBytes == 2) {
            Procedure div16s = getProgramScope().getLocalProcedure("div16s");
            if(div16s != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "div16s",
                     list(dividend, new ConstantInteger(c, SymbolType.SWORD)),
                     assign.getSource(), assign.getComments());
               call.setProcedure(div16s.getRef());
               stmtIt.set(call);
               return true;
            }
            return false;
         }

         // 16-bit unsigned constant division (uint16 / const)
         if(!isSigned && divBytes == 2) {
            if(c <= 255) {
               // Fast shift-subtract constant algorithm takes ~340 cycles (3.8x faster than div16u8u's ~1280 cycles!)
               if(occurrences >= 3) {
                  // Synthesize dedicated ad-hoc helper function to avoid duplicating 22 bytes per site
                  Procedure helper = getOrCreateHelperProcedure16uDiv(c);
                  StatementCall call = new StatementCall(assign.getlValue(), helper.getLocalName(),
                        list(dividend), assign.getSource(), assign.getComments());
                  call.setProcedure(helper.getRef());
                  stmtIt.set(call);
                  return true;
               } else {
                  // Occurs 1-2 times: inline individual code directly (no call overhead)
                  return false;
               }
            } else {
               // For c > 255: redirect to standard library div16u
               Procedure div16u = getProgramScope().getLocalProcedure("div16u");
               if(div16u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div16u",
                        list(dividend, new ConstantInteger(c, SymbolType.WORD)),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(div16u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         }

         // 24-bit constant division (tribyte / const)
         if(divBytes == 3) {
            String procName = isSigned ? "div24s" : "div24u";
            Procedure div24 = getProgramScope().getLocalProcedure(procName);
            if(div24 != null) {
               StatementCall call = new StatementCall(assign.getlValue(), procName,
                     list(dividend, new ConstantInteger(c, isSigned ? SymbolType.STRIBYTE : SymbolType.TRIBYTE)),
                     assign.getSource(), assign.getComments());
               call.setProcedure(div24.getRef());
               stmtIt.set(call);
               return true;
            }
         }

         // 32-bit constant division (dword / const)
         if(divBytes == 4) {
            if(!isSigned && c <= 65535) {
               Procedure div32u16u = getProgramScope().getLocalProcedure("div32u16u");
               if(div32u16u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div32u16u",
                        list(dividend, new ConstantInteger(c, SymbolType.WORD)),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(div32u16u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
            String procName = isSigned ? "div32s" : "div32u";
            Procedure div32 = getProgramScope().getLocalProcedure(procName);
            if(div32 != null) {
               StatementCall call = new StatementCall(assign.getlValue(), procName,
                     list(dividend, new ConstantInteger(c, isSigned ? SymbolType.SDWORD : SymbolType.DWORD)),
                     assign.getSource(), assign.getComments());
               call.setProcedure(div32.getRef());
               stmtIt.set(call);
               return true;
            }
         }
      } else {
         // Dynamic division (variable divisor, e.g. a / b)
         if(divBytes == 1) {
            if(isSigned) {
               Procedure div8s = getProgramScope().getLocalProcedure("div8s");
               if(div8s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div8s",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div8s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               Procedure div8u = getProgramScope().getLocalProcedure("div8u");
               if(div8u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div8u",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div8u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         } else if(divBytes == 2) {
            if(isSigned) {
               Procedure div16s = getProgramScope().getLocalProcedure("div16s");
               if(div16s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div16s",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div16s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               if(divisorBytes == 1) {
                  Procedure div16u8u = getProgramScope().getLocalProcedure("div16u8u");
                  if(div16u8u != null) {
                     StatementCall call = new StatementCall(assign.getlValue(), "div16u8u",
                           list(dividend, divisor), assign.getSource(), assign.getComments());
                     call.setProcedure(div16u8u.getRef());
                     stmtIt.set(call);
                     return true;
                  }
               }
               Procedure div16u = getProgramScope().getLocalProcedure("div16u");
               if(div16u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div16u",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div16u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         } else if(divBytes == 3) {
            String procName = isSigned ? "div24s" : "div24u";
            Procedure div24 = getProgramScope().getLocalProcedure(procName);
            if(div24 != null) {
               StatementCall call = new StatementCall(assign.getlValue(), procName,
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(div24.getRef());
               stmtIt.set(call);
               return true;
            }
         } else if(divBytes == 4) {
            if(!isSigned && divisorBytes <= 2) {
               Procedure div32u16u = getProgramScope().getLocalProcedure("div32u16u");
               if(div32u16u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div32u16u",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div32u16u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
            String procName = isSigned ? "div32s" : "div32u";
            Procedure div32 = getProgramScope().getLocalProcedure(procName);
            if(div32 != null) {
               StatementCall call = new StatementCall(assign.getlValue(), procName,
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(div32.getRef());
               stmtIt.set(call);
               return true;
            }
         }
      }
      return false;
   }

   private boolean rewriteModulo(StatementAssignment assign, ListIterator<Statement> stmtIt, Map<Long, Integer> divConstCounts) {
      RValue dividend = assign.getrValue1();
      RValue divisor = assign.getrValue2();
      SymbolType modType = SymbolTypeInference.inferType(getProgramScope(), dividend);
      SymbolType divisorType = SymbolTypeInference.inferType(getProgramScope(), divisor);
      boolean isSigned = isSigned(modType) || isSigned(divisorType);
      int modBytes = modType.getSizeBytes();

      if(divisor instanceof ConstantInteger constInt) {
         long c = constInt.getInteger();
         double pow2 = Math.log(c) / Math.log(2);
         if(pow2 >= 0.0 && Math.round(pow2) == pow2 && !isSigned) {
            return false; // Power of 2 bitwise AND in Pass 2
         }
      }

      if(modBytes == 1) {
         if(isSigned) {
            Procedure mod8s = getProgramScope().getLocalProcedure("mod8s");
            if(mod8s != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "mod8s",
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(mod8s.getRef());
               stmtIt.set(call);
               return true;
            }
         } else {
            Procedure mod8u = getProgramScope().getLocalProcedure("mod8u");
            if(mod8u != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "mod8u",
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(mod8u.getRef());
               stmtIt.set(call);
               return true;
            }
         }
      } else if(modBytes == 2) {
         if(isSigned) {
            Procedure mod16s = getProgramScope().getLocalProcedure("mod16s");
            if(mod16s != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "mod16s",
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(mod16s.getRef());
               stmtIt.set(call);
               return true;
            }
         } else {
            Procedure mod16u = getProgramScope().getLocalProcedure("mod16u");
            if(mod16u != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "mod16u",
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(mod16u.getRef());
               stmtIt.set(call);
               return true;
            }
            Procedure modr16u = getProgramScope().getLocalProcedure("modr16u");
            if(modr16u != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "modr16u",
                     list(dividend, divisor, new ConstantInteger(0L, SymbolType.WORD)),
                     assign.getSource(), assign.getComments());
               call.setProcedure(modr16u.getRef());
               stmtIt.set(call);
               return true;
            }
         }
      } else if(modBytes == 3) {
         String procName = isSigned ? "mod24s" : "mod24u";
         Procedure mod24 = getProgramScope().getLocalProcedure(procName);
         if(mod24 != null) {
            StatementCall call = new StatementCall(assign.getlValue(), procName,
                  list(dividend, divisor), assign.getSource(), assign.getComments());
            call.setProcedure(mod24.getRef());
            stmtIt.set(call);
            return true;
         }
      } else if(modBytes == 4) {
         String procName = isSigned ? "mod32s" : "mod32u";
         Procedure mod32 = getProgramScope().getLocalProcedure(procName);
         if(mod32 != null) {
            StatementCall call = new StatementCall(assign.getlValue(), procName,
                  list(dividend, divisor), assign.getSource(), assign.getComments());
            call.setProcedure(mod32.getRef());
            stmtIt.set(call);
            return true;
         }
      }
      return false;
   }

   private boolean rewriteMultiply(StatementAssignment assign, ListIterator<Statement> stmtIt, Map<Long, Integer> mulConstCounts) {
      RValue left = assign.getrValue1();
      RValue right = assign.getrValue2();
      SymbolType mulType = SymbolTypeInference.inferType(getProgramScope(), left);
      SymbolType rightType = SymbolTypeInference.inferType(getProgramScope(), right);
      boolean isSigned = isSigned(mulType) || isSigned(rightType);
      int leftBytes = mulType.getSizeBytes();
      int rightBytes = rightType.getSizeBytes();

      if(right instanceof ConstantInteger constInt) {
         long c = constInt.getInteger();
         if(c <= 1) return false;
         double pow2 = Math.log(c) / Math.log(2);
         if(pow2 >= 0.0 && Math.round(pow2) == pow2) {
            return false; // Power of 2 shift
         }

         int occurrences = mulConstCounts.getOrDefault(c, 0);

         // Analyze shift-add cycle and byte cost
         int trailingZeros = Long.numberOfTrailingZeros(c);
         long odd = c >> trailingZeros;
         int setBits = Long.bitCount(odd);
         int addCount = Math.max(0, setBits - 1);
         int shiftCount = Math.max(0, (64 - Long.numberOfLeadingZeros(odd)) - 1) + trailingZeros;
         int estimatedBytes = (shiftCount * 2) + (addCount * 3);

         // Only deduplicate into normal table-free mul8u if occurrences >= 4 and footprint savings exceed call overhead
         if(!isSigned && leftBytes == 1 && c <= 255) {
            if(occurrences >= 4 && estimatedBytes >= 10) {
               Procedure mul8u = getProgramScope().getLocalProcedure("mul8u");
               if(mul8u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mul8u",
                        list(left, new ConstantInteger(c, SymbolType.BYTE)),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(mul8u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         }
      } else {
         // Dynamic variable multiplication (normal table-free binary multiplication)
         if(leftBytes == 1 && rightBytes == 1) {
            if(isSigned) {
               Procedure mul8s = getProgramScope().getLocalProcedure("mul8s");
               if(mul8s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mul8s",
                        list(left, right), assign.getSource(), assign.getComments());
                  call.setProcedure(mul8s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               Procedure mul8u = getProgramScope().getLocalProcedure("mul8u");
               if(mul8u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mul8u",
                        list(left, right), assign.getSource(), assign.getComments());
                  call.setProcedure(mul8u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         } else if(leftBytes <= 2 && rightBytes <= 2) {
            if(isSigned) {
               Procedure mul16s = getProgramScope().getLocalProcedure("mul16s");
               if(mul16s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mul16s",
                        list(left, right), assign.getSource(), assign.getComments());
                  call.setProcedure(mul16s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               Procedure mul16u = getProgramScope().getLocalProcedure("mul16u");
               if(mul16u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mul16u",
                        list(left, right), assign.getSource(), assign.getComments());
                  call.setProcedure(mul16u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
         }
      }
      return false;
   }
}
