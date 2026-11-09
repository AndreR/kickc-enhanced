package dk.camelot64.kickc.passes;

import dk.camelot64.kickc.model.Comment;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.VariableBuilder;
import dk.camelot64.kickc.model.iterator.ProgramValueIterator;
import dk.camelot64.kickc.model.operators.Operators;
import dk.camelot64.kickc.model.statements.StatementAssignment;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypePointer;
import dk.camelot64.kickc.model.types.SymbolTypeInference;
import dk.camelot64.kickc.model.values.*;

import java.util.concurrent.atomic.AtomicBoolean;

/** Fnds derefidx with word indices - and de-inlines them (converting to + and normal deref). */
public class Pass2DeInlineWordDerefIdx extends Pass2SsaOptimization {

   public Pass2DeInlineWordDerefIdx(Program program) {
      super(program);
   }

   @Override
   public boolean step() {
      AtomicBoolean optimized = new AtomicBoolean(false);
      ProgramValueIterator.execute(getGraph(), (programValue, currentStmt, stmtIt, currentBlock) -> {
         if(programValue.get() instanceof PointerDereferenceIndexed) {
            PointerDereferenceIndexed dereferenceIndexed = (PointerDereferenceIndexed) programValue.get();
            RValue indexValue = dereferenceIndexed.getIndex();
            SymbolType indexType = SymbolTypeInference.inferType(getProgramScope(), indexValue);
            if(indexType.getSizeBytes()>1) {
               // Check if the target is a static array known to fit within 256 bytes total
               Long totalBytes = getArrayTotalBytes(dereferenceIndexed.getPointer());
               if(totalBytes != null && totalBytes <= 256L) {
                  Scope currentScope = getProgramScope().getScope(currentBlock.getScope());
                  Variable byteIndexVar = VariableBuilder.createIntermediate(currentScope, SymbolType.BYTE, getProgram());
                  stmtIt.previous();
                  StatementAssignment castAssignment = new StatementAssignment(
                     (LValue) byteIndexVar.getRef(),
                     Operators.getCastUnary(SymbolType.BYTE),
                     indexValue,
                     true,
                     currentStmt.getSource(),
                     Comment.NO_COMMENTS
                  );
                  stmtIt.add(castAssignment);
                  stmtIt.next();
                  dereferenceIndexed.setIndex(byteIndexVar.getRef());
                  getLog().append("Narrowed array word index to byte for direct 6502 indexed addressing: " + currentStmt.toString(getProgram(), false));
                  optimized.set(true);
                  return;
               }

               // Index is multiple bytes - de-inline it
               getLog().append("De-inlining pointer[w] to *(pointer+w)   "+currentStmt.toString(getProgram(), false));
               Scope currentScope = getProgramScope().getScope(currentBlock.getScope());
               SymbolType pointerType = Operators.PLUS.inferType(SymbolTypeInference.inferType(getProgramScope(), dereferenceIndexed.getPointer()), SymbolTypeInference.inferType(getProgramScope(), dereferenceIndexed.getIndex()));
               Variable tmpVar = VariableBuilder.createIntermediate(currentScope, pointerType, getProgram());
               stmtIt.previous();
               StatementAssignment tmpVarAssignment = new StatementAssignment((LValue) tmpVar.getRef(), dereferenceIndexed.getPointer(), Operators.PLUS, indexValue, true, currentStmt.getSource(), Comment.NO_COMMENTS);
               stmtIt.add(tmpVarAssignment);
               stmtIt.next();
               programValue.set(new PointerDereferenceSimple(tmpVar.getRef()));
               optimized.set(true);
            }
         }
      });
      return optimized.get();
   }

   private Long getArrayTotalBytes(RValue pointer) {
      SymbolVariableRef symbolRef = null;
      if(pointer instanceof SymbolVariableRef) {
         symbolRef = (SymbolVariableRef) pointer;
      } else if(pointer instanceof ConstantSymbolPointer) {
         SymbolRef sym = ((ConstantSymbolPointer) pointer).getToSymbol();
         if(sym instanceof SymbolVariableRef) {
            symbolRef = (SymbolVariableRef) sym;
         }
      }
      if(symbolRef != null) {
         Variable var = getProgramScope().getVariable(symbolRef);
         if(var != null && var.isArray()) {
            SymbolType type = var.getType();
            if(type instanceof SymbolTypePointer) {
               SymbolType elemType = ((SymbolTypePointer) type).getElementType();
               int elemBytes = elemType.getSizeBytes();
               if(var.getArraySize() != null) {
                  try {
                     ConstantLiteral lit = var.getArraySize().calculateLiteral(getProgramScope());
                     if(lit instanceof ConstantInteger) {
                        return ((ConstantInteger) lit).getInteger() * elemBytes;
                     }
                  } catch(Exception e) {
                     // ignore
                  }
               }
               ConstantValue initValue = var.getInitValue();
               if(initValue instanceof ConstantArrayList) {
                  return (long) ((ConstantArrayList) initValue).getElements().size() * elemBytes;
               }
               if(initValue instanceof ConstantArrayFilled) {
                  try {
                     ConstantLiteral lit = ((ConstantArrayFilled) initValue).getSize().calculateLiteral(getProgramScope());
                     if(lit instanceof ConstantInteger) {
                        return ((ConstantInteger) lit).getInteger() * elemBytes;
                     }
                  } catch(Exception e) {
                     // ignore
                  }
               }
            }
         }
      }
      return null;
   }

}
