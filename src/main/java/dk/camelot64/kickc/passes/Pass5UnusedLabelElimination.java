package dk.camelot64.kickc.passes;



import dk.camelot64.kickc.asm.*;

import dk.camelot64.kickc.model.Program;

import dk.camelot64.kickc.model.statements.Statement;

import dk.camelot64.kickc.model.statements.StatementAsm;



import java.util.ArrayList;

import java.util.LinkedHashSet;

import java.util.List;

import java.util.Set;



/**

 * Optimize assembler code by removing all unused labels

 */

public class Pass5UnusedLabelElimination extends Pass5AsmOptimization {



   public Pass5UnusedLabelElimination(Program program) {

      super(program);

   }



   public boolean optimize() {

      Set<String> usedLabels = findUsedLabels();

      Set<String> modifiedScopes = new LinkedHashSet<>();

      List<AsmLine> removeLines = findUnusedLabelLines(usedLabels, modifiedScopes);

      if(!removeLines.isEmpty()) {

         remove(removeLines);

         for(String scope : modifiedScopes) {

            markScopeDirty(scope);

         }

         invalidateRoundStructure();

         return true;

      }

      return false;

   }



   /** Find all labels that are used. */

   private Set<String> findUsedLabels() {

      Set<String> usedLabels = new LinkedHashSet<>();

      String currentScope = "";

      for(AsmChunk chunk : getAsmProgram().getChunks()) {

         for(AsmLine line : chunk.getLines()) {

            if(line instanceof AsmScopeBegin) {

               currentScope = ((AsmScopeBegin) line).getLabel();

            } else if(line instanceof AsmScopeEnd) {

               currentScope = "";

            } else if(line instanceof AsmBasicUpstart) {

               String labelStr = currentScope + "::" + ((AsmBasicUpstart) line).getLabel();

               usedLabels.add(labelStr);

            } else if(line instanceof AsmInstruction) {

               AsmInstruction instruction = (AsmInstruction) line;

               if(instruction.getCpuOpcode().isJump() && instruction.getOperandJumpTarget()!=null) {

                  String labelStr = currentScope + "::" + instruction.getOperandJumpTarget();

                  usedLabels.add(labelStr);

               }
               addIdentifierTokens(instruction.getOperand1(), currentScope, usedLabels);
               addIdentifierTokens(instruction.getOperand2(), currentScope, usedLabels);

            } else if(line instanceof AsmDataKickAsm) {

               addIdentifierTokens(((AsmDataKickAsm) line).getKickAsmCode(), currentScope, usedLabels);

            } else if(line instanceof AsmInlineKickAsm) {

               addIdentifierTokens(((AsmInlineKickAsm) line).getAsm(), currentScope, usedLabels);

            }

         }

      }

      usedLabels.add("::"+getProgram().getStartProcedure().getLocalName());

      return usedLabels;

   }

   private static boolean isIdentChar(char c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
   }

   private static void addIdentifierTokens(String s, String currentScope, Set<String> usedLabels) {
      if(s == null || s.isEmpty()) return;
      int len = s.length();
      int i = 0;
      while(i < len) {
         if(isIdentChar(s.charAt(i))) {
            int start = i;
            i++;
            while(i < len && isIdentChar(s.charAt(i))) {
               i++;
            }
            char first = s.charAt(start);
            if(first < '0' || first > '9') {
               String token = s.substring(start, i);
               usedLabels.add(currentScope + "::" + token);
               usedLabels.add("::" + token);
            }
         } else {
            i++;
         }
      }
   }



   /** Find all ASM lines that are unused labels. */

   private List<AsmLine> findUnusedLabelLines(Set<String> usedLabels, Set<String> modifiedScopes) {

      List<AsmLine> removeLines = new ArrayList<>();

      String currentScope = "";

      for(AsmChunk chunk : getAsmProgram().getChunks()) {

         Integer statementIdx = chunk.getStatementIdx();

         if(statementIdx != null) {

            Statement statement = getProgram().getStatementInfos().getStatement(statementIdx);

            if(statement instanceof StatementAsm) {

               // Skip ASM statement

               continue;

            }

         }

         for(AsmLine line : chunk.getLines()) {

            if(line instanceof AsmScopeBegin) {

               currentScope = ((AsmScopeBegin) line).getLabel();

            } else if(line instanceof AsmScopeEnd) {

               currentScope = "";

            } else if(line instanceof AsmLabel) {

               AsmLabel label = (AsmLabel) line;

               if(label.isNoOptimize()) {

                  continue;

               }

               String labelStr = currentScope + "::" + label.getLabel();

               if(!labelStr.contains("!") && !usedLabels.contains(labelStr)) {

                  removeLines.add(label);

                  modifiedScopes.add(currentScope);

               }

            }

         }

      }

      return removeLines;

   }





}

