package dk.camelot64.kickc.passes.unwinding;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.InternalError;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.symbols.ArraySpec;
import dk.camelot64.kickc.model.symbols.ProgramScope;
import dk.camelot64.kickc.model.symbols.StructDefinition;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.LValue;
import dk.camelot64.kickc.model.values.RValue;
import dk.camelot64.kickc.model.values.ValueList;

import java.util.ListIterator;

/** A struct value list - typically an initializer <code>struct Point p = { x, y };</code>. */
public class ValueSourceStructValueList extends ValueSourceBase {

   private final StructDefinition structDefinition;
   private final ValueList valueList;

   public ValueSourceStructValueList(ValueList valueList, StructDefinition structDefinition) {
      this.valueList = valueList;
      this.structDefinition = structDefinition;
   }

   @Override
   public SymbolType getSymbolType() {
      return structDefinition.getType();
   }

   @Override
   public ArraySpec getArraySpec() {
      return null;
   }

   @Override
   protected boolean isStructClassic() {
      return false;
   }

   @Override
   public RValue getSimpleValue(ProgramScope programScope) {
      throw new InternalError("Not a simple value");
   }

   @Override
   public LValue getBulkLValue(ProgramScope scope) {
      throw new InternalError("Not a bulk value");
   }

   @Override
   public RValue getBulkRValue(ProgramScope scope) {
      throw new InternalError("Not a bulk value");
   }

   public boolean isDesignatedUnion() {
      if(!structDefinition.isUnion() || valueList.getList().isEmpty()) return false;
      for(RValue rv : valueList.getList()) {
         if(!(rv instanceof dk.camelot64.kickc.model.values.UnionDesignator)) return false;
      }
      return true;
   }

   public ValueList getValueList() {
      return valueList;
   }

   @Override
   public ValueSource getMemberUnwinding(String memberName, Program program, ProgramScope programScope, Statement currentStmt, ListIterator<Statement> stmtIt, Graph.Block currentBlock) {
      if(isDesignatedUnion()) {
         for(RValue rv : valueList.getList()) {
            dk.camelot64.kickc.model.values.UnionDesignator ud = (dk.camelot64.kickc.model.values.UnionDesignator) rv;
            if(ud.getMemberName().equals(memberName)) {
               return ValueSourceFactory.getValueSource(ud.getMemberValue(), program, programScope, currentStmt, stmtIt, currentBlock);
            }
         }
         return null;
      }
      int memberIndex = structDefinition.isUnion() ? 0 : getMemberNames(programScope).indexOf(memberName);
      if(memberIndex < 0 || memberIndex >= valueList.getList().size()) {
         dk.camelot64.kickc.model.symbols.Variable memberVar = structDefinition.getMember(memberName);
         if(memberVar != null) {
            dk.camelot64.kickc.model.values.ConstantValue zeroVal = dk.camelot64.kickc.model.Initializers.createZeroValue(
                  new dk.camelot64.kickc.model.Initializers.ValueTypeSpec(memberVar.getType()), currentStmt != null ? currentStmt.getSource() : null);
            return ValueSourceFactory.getValueSource(zeroVal, program, programScope, currentStmt, stmtIt, currentBlock);
         }
         return null;
      }
      final RValue memberValue = valueList.getList().get(memberIndex);
      return ValueSourceFactory.getValueSource(memberValue, program, programScope, currentStmt, stmtIt, currentBlock);
   }

}
