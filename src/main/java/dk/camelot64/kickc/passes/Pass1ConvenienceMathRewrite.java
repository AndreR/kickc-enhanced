package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.operators.Operator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.statements.StatementCall;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.types.SymbolTypeIntegerFixed;
import dk.camelot64.kickc.model.values.*;

import java.util.*;

/**
 * Pass 1: Adaptive Convenience-C Arithmetic Synthesizer & Code Size Guardian.
 * Evaluates CPU cycles and code footprint for non-power-of-two divisions, multiplications, and modulo.
 * Automatically decides for each constant division/multiplication whether to generate individual
 * inline code or redirect to the standard library based on occurrence frequency and code size costs.
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
      return scope.equals("div8u") || scope.equals("divr8u") || scope.equals("div16u")
            || scope.equals("div16u8u") || scope.equals("div8s") || scope.equals("div16s")
            || scope.equals("div10u") || scope.equals("mod10u") || scope.equals("div16u10") || scope.equals("mod16u10")
            || scope.equals("mod8u") || scope.equals("mod8s") || scope.equals("mod16u") || scope.equals("mod16s")
            || scope.equals("modr16u") || scope.equals("div32u16u")
            || scope.equals("mul8u") || scope.equals("mul8s") || scope.equals("mul8su")
            || scope.equals("mul16u") || scope.equals("mul16s");
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
            // Signed power-of-2 division cannot use pure right-shift without sign adjustment in C99
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
         // Heuristic:
         // - If occurrences <= 2 and exact reciprocal exists: individual code (Pass 2 inlined reciprocal)
         // - If c == 10 and occurrences >= 3: redirect to div10u() lib helper (22 cycles, 0 bloat)
         // - If occurrences >= 4: redirect to div8u() lib function to prevent multi-call footprint bloat
         if(!isSigned && divBytes == 1 && c <= 255) {
            if(c == 10) {
               if(occurrences >= 3) {
                  Procedure div10u = getProgramScope().getLocalProcedure("div10u");
                  if(div10u != null) {
                     StatementCall call = new StatementCall(assign.getlValue(), "div10u",
                           list(dividend),
                           assign.getSource(), assign.getComments());
                     call.setProcedure(div10u.getRef());
                     stmtIt.set(call);
                     return true;
                  }
               }
               return false; // Individual code (Pass 2 reciprocal)
            }
            Pass2MultiplyToShiftRewriting.Reciprocal recip = Pass2MultiplyToShiftRewriting.findReciprocal(c);
            if(occurrences <= 2 && recip != null) {
               return false; // Individual code (Pass 2 reciprocal)
            }
            Procedure div8u = getProgramScope().getLocalProcedure("div8u");
            if(div8u != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "div8u",
                     list(dividend, new ConstantInteger(c, SymbolType.BYTE)),
                     assign.getSource(), assign.getComments());
               call.setProcedure(div8u.getRef());
               stmtIt.set(call);
               return true;
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
         // Heuristic:
         // - If c == 10:
         //     If occurrences >= 3: redirect to div16u10() (24-byte specialized helper, ~340 cycles)
         //     Else: individual code (inline KickC fragment vwuz1_div_10)
         // - If c <= 255:
         //     If occurrences >= 3: redirect to div16u8u(dividend, c) to avoid repeating 22 bytes per site
         //     Else: individual code (inline KickC fragment vwuz1_div_vbuc1)
         // - If c > 255:
         //     Redirect to div16u(dividend, c)
         if(!isSigned && divBytes == 2) {
            if(c == 10) {
               if(occurrences >= 3) {
                  Procedure div16u10 = getProgramScope().getLocalProcedure("div16u10");
                  if(div16u10 != null) {
                     StatementCall call = new StatementCall(assign.getlValue(), "div16u10",
                           list(dividend),
                           assign.getSource(), assign.getComments());
                     call.setProcedure(div16u10.getRef());
                     stmtIt.set(call);
                     return true;
                  }
               }
               return false; // Individual code (inline fragment)
            } else if(c <= 255) {
               if(occurrences >= 3) {
                  Procedure div16u8u = getProgramScope().getLocalProcedure("div16u8u");
                  if(div16u8u != null) {
                     StatementCall call = new StatementCall(assign.getlValue(), "div16u8u",
                           list(dividend, new ConstantInteger(c, SymbolType.BYTE)),
                           assign.getSource(), assign.getComments());
                     call.setProcedure(div16u8u.getRef());
                     stmtIt.set(call);
                     return true;
                  }
               }
               return false; // Individual code (inline fragment)
            } else {
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

         // 32-bit unsigned constant division
         if(!isSigned && divBytes == 4 && c <= 65535) {
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
      } else {
         // Dynamic division (variable divisor, e.g. a / b)
         if(divBytes == 1) {
            if(isSigned) {
               // int8 / int8 signed division
               Procedure div8s = getProgramScope().getLocalProcedure("div8s");
               if(div8s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div8s",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div8s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               // uint8 / uint8 unsigned division
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
               // int16 / int16 signed division
               Procedure div16s = getProgramScope().getLocalProcedure("div16s");
               if(div16s != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "div16s",
                        list(dividend, divisor), assign.getSource(), assign.getComments());
                  call.setProcedure(div16s.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else {
               // uint16 / uint8 or uint16 / uint16
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
         } else if(divBytes == 4 && !isSigned && divisorBytes <= 2) {
            Procedure div32u16u = getProgramScope().getLocalProcedure("div32u16u");
            if(div32u16u != null) {
               StatementCall call = new StatementCall(assign.getlValue(), "div32u16u",
                     list(dividend, divisor), assign.getSource(), assign.getComments());
               call.setProcedure(div32u16u.getRef());
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
         int occurrences = divConstCounts.getOrDefault(c, 0);
         if(c == 10 && !isSigned) {
            if(modBytes == 1 && occurrences >= 3) {
               Procedure mod10u = getProgramScope().getLocalProcedure("mod10u");
               if(mod10u != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mod10u",
                        list(dividend),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(mod10u.getRef());
                  stmtIt.set(call);
                  return true;
               }
            } else if(modBytes == 2 && occurrences >= 3) {
               Procedure mod16u10 = getProgramScope().getLocalProcedure("mod16u10");
               if(mod16u10 != null) {
                  StatementCall call = new StatementCall(assign.getlValue(), "mod16u10",
                        list(dividend),
                        assign.getSource(), assign.getComments());
                  call.setProcedure(mod16u10.getRef());
                  stmtIt.set(call);
                  return true;
               }
            }
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
