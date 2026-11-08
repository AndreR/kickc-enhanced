package dk.camelot64.kickc.model;

import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.values.RValue;

/**
 * A compile-time assertion (C11/C23 static_assert / _Static_assert).
 */
public class StaticAssert {

   private RValue condition;
   private String message;
   private StatementSource source;

   public StaticAssert(RValue condition, String message, StatementSource source) {
      this.condition = condition;
      this.message = message;
      this.source = source;
   }

   public RValue getCondition() {
      return condition;
   }

   public void setCondition(RValue condition) {
      this.condition = condition;
   }

   public String getMessage() {
      return message;
   }

   public StatementSource getSource() {
      return source;
   }

}
