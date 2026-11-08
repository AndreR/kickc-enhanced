package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Pass 5 Optimization: Zero-Waste Page-Boundary Loop Alignment.
 * Identifies tight loops whose byte length L <= threshold.
 * Inserts KickAssembler directive:
 *   .if ((* & $ff) > (256 - L)) .align $100
 * before the loop header label.
 *
 * If the loop fits within the current 256-byte page, 0 bytes are padded.
 * If placing the loop at the current PC would cause it to cross a 256-byte page boundary,
 * it is aligned to $100, eliminating page boundary crossing penalties on backward branches
 * and internal memory accesses.
 */
public class Pass5ZeroWasteLoopAlignment extends Pass5AsmOptimization {

   public Pass5ZeroWasteLoopAlignment(Program program) {
      super(program);
   }

   private static class LineRef {
      final int index;
      final AsmChunk chunk;
      final AsmLine line;
      final String scope;
      final String segment;
      final int offset;

      LineRef(int index, AsmChunk chunk, AsmLine line, String scope, String segment, int offset) {
         this.index = index;
         this.chunk = chunk;
         this.line = line;
         this.scope = scope;
         this.segment = segment;
         this.offset = offset;
      }
   }

   @Override
   public boolean optimize() {
      int threshold = getProgram().getLoopAlignThreshold();
      if(threshold <= 0) {
         return false;
      }

      // Collect all lines with scope, segment, and linear byte offset
      List<LineRef> allLines = new ArrayList<>();
      String currentScope = null;
      String currentSegment = null;
      int currentOffset = 0;
      int lineIdx = 0;

      for(AsmChunk chunk : getAsmProgram().getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
               currentOffset = 0;
            } else if(line instanceof AsmScopeEnd) {
               currentScope = null;
               currentOffset = 0;
            } else if(line instanceof AsmSegment) {
               currentSegment = ((AsmSegment) line).getName();
            }

            allLines.add(new LineRef(lineIdx++, chunk, line, currentScope, currentSegment, currentOffset));
            currentOffset += line.getLineBytes();
         }
      }

      // Map from scope::label -> earliest LineRef defining that label
      Map<String, LineRef> labelRefs = new HashMap<>();
      for(int i = 0; i < allLines.size(); i++) {
         LineRef ref = allLines.get(i);
         if(ref.line instanceof AsmLabel) {
            String label = ((AsmLabel) ref.line).getLabel();
            if(!label.startsWith("!")) {
               String key = (ref.scope != null ? ref.scope + "::" : "") + label;
               // Find the earliest label at the exact same offset
               LineRef earliest = ref;
               for(int j = i - 1; j >= 0; j--) {
                  LineRef prev = allLines.get(j);
                  if(prev.line instanceof AsmComment) continue;
                  if(prev.line instanceof AsmLabel && prev.offset == ref.offset) {
                     earliest = prev;
                  } else {
                     break;
                  }
               }
               labelRefs.put(key, earliest);
            }
         }
      }

      // Map to track max loop length per target label LineRef
      Map<LineRef, Integer> loopLengths = new LinkedHashMap<>();

      // Detect backward branches
      for(int i = 0; i < allLines.size(); i++) {
         LineRef branchRef = allLines.get(i);
         if(branchRef.line instanceof AsmInstruction) {
            AsmInstruction instr = (AsmInstruction) branchRef.line;
            // Backward relative branch or jump (bne, beq, bcs, bcc, bmi, bpl, bvs, bvc, jmp)
            if(instr.getCpuOpcode() != null && (instr.getCpuOpcode().getAddressingMode() == CpuAddressingMode.REL || "jmp".equals(instr.getCpuOpcode().getMnemonic()))) {
               String target = instr.getOperandJumpTarget();
               if(target != null && !target.startsWith("!")) {
                  String key = (branchRef.scope != null ? branchRef.scope + "::" : "") + target;
                  LineRef targetRef = labelRefs.get(key);
                  if(targetRef != null && targetRef.offset < branchRef.offset) {
                     // Check segment compatibility
                     if(targetRef.segment != null && targetRef.segment.equals(branchRef.segment)) {
                        // Verify that all lines within the loop are contiguous in the same segment without data chunks
                        boolean validLoop = true;
                        for(int k = targetRef.index; k <= branchRef.index; k++) {
                           LineRef inside = allLines.get(k);
                           if(inside.segment != null && !inside.segment.equals(targetRef.segment)) {
                              validLoop = false;
                              break;
                           }
                           if(inside.line instanceof AsmDataAlignment || inside.line instanceof AsmDataNumeric ||
                              inside.line instanceof AsmDataZeroFill || inside.line instanceof AsmDataString ||
                              inside.line instanceof AsmLoopAlign) {
                              validLoop = false;
                              break;
                           }
                        }

                        if(validLoop) {
                           int loopLen = (branchRef.offset + branchRef.line.getLineBytes()) - targetRef.offset;
                           if(loopLen > 0 && loopLen <= threshold && loopLen < 256) {
                              int prevLen = loopLengths.getOrDefault(targetRef, 0);
                              if(loopLen > prevLen) {
                                 loopLengths.put(targetRef, loopLen);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      // Insert alignment directives
      int insertedCount = 0;
      for(Map.Entry<LineRef, Integer> entry : loopLengths.entrySet()) {
         LineRef targetRef = entry.getKey();
         int loopLen = entry.getValue();

         // Check if already aligned immediately before
         boolean alreadyAligned = false;
         for(int j = targetRef.index - 1; j >= 0; j--) {
            LineRef prev = allLines.get(j);
            if(prev.line instanceof AsmComment) continue;
            if(prev.line instanceof AsmLoopAlign || prev.line instanceof AsmDataAlignment) {
               alreadyAligned = true;
            }
            break;
         }

         if(!alreadyAligned) {
            targetRef.chunk.addLineBefore(targetRef.line, new AsmLoopAlign(loopLen));
            insertedCount++;
         }
      }

      return insertedCount > 0;
   }

}
