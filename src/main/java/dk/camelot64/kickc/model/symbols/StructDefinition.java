package dk.camelot64.kickc.model.symbols;

import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeStruct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A struct definition containing a set of variables.
 * The struct definition can either represent a struct (member memory-layout is linearly consecutive ) or a union (member start at the same memory address)
 */
public class StructDefinition extends Scope {

   private boolean isUnion;
   private boolean packReorder = false;

   public StructDefinition(String name, boolean isUnion, Scope parentScope) {
      super(name, parentScope, SEGMENT_DATA_DEFAULT);
      this.isUnion = isUnion;
   }

   @Override
   public SymbolType getType() {
      return new SymbolTypeStruct(this, false, false);
   }

   public boolean isUnion() {
      return isUnion;
   }

   public boolean isPackReorder() {
      return packReorder;
   }

   public void setPackReorder(boolean packReorder) {
      this.packReorder = packReorder;
   }

   /**
    * Returns the struct members in memory-layout order.
    * When {@link #isPackReorder()} is enabled (and the struct is not a union, has no bitfields, and has no explicit
    * member offsets), fields are stably grouped by byte size (1-byte, 2-byte, 4-byte, then other sizes), preserving
    * relative declaration order within each size group.
    */
   public List<Variable> getLayoutMembers(ProgramScope programScope) {
      Collection<Variable> declared = getAllVars(false);
      if(!packReorder || isUnion || hasBitfields()) {
         return new ArrayList<>(declared);
      }
      for(Variable member : declared) {
         if(member.getStructMemberOffset() != null) {
            return new ArrayList<>(declared);
         }
      }
      List<Variable> size1 = new ArrayList<>();
      List<Variable> size2 = new ArrayList<>();
      List<Variable> size4 = new ArrayList<>();
      List<Variable> sizeOther = new ArrayList<>();
      for(Variable member : declared) {
         int sz = SymbolTypeStruct.getMemberSizeBytes(member.getType(), member.getArraySize(), programScope);
         if(sz == 1) {
            size1.add(member);
         } else if(sz == 2) {
            size2.add(member);
         } else if(sz == 4) {
            size4.add(member);
         } else {
            sizeOther.add(member);
         }
      }
      List<Variable> reordered = new ArrayList<>(declared.size());
      reordered.addAll(size1);
      reordered.addAll(size2);
      reordered.addAll(size4);
      reordered.addAll(sizeOther);
      return reordered;
   }

   public boolean hasBitfields() {
      for(Variable member : getAllVars(false)) {
         if(member.isBitField()) return true;
      }
      return false;
   }

   /**
    * Get a struct member variable
    *
    * @param name The name of the member
    * @return The member variable
    */
   public Variable getMember(String name) {
      for(Variable member : getAllVars(false)) {
         if(member.getLocalName().equals(name)) {
            return member;
         }
      }
      return null;
   }

   /**
    * Get the number of bytes that the member data is offset from the start of the struct
    *
    * @param member The member to find offset for
    * @return The byte offset of the start of the member data
    */
   public long getMemberByteOffset(Variable member, ProgramScope programScope) {
      if(member.getStructMemberOffset() != null)
         return member.getStructMemberOffset();
      if(member.isBitField()) {
         Variable container = getMember(member.getBitFieldContainerName());
         if(container != null) {
            return getMemberByteOffset(container, programScope);
         }
      }
      if(isUnion)
         return 0;
      long byteOffset = 0;
      for(Variable structMember : getLayoutMembers(programScope)) {
         if(structMember.equals(member)) {
            break;
         } else {
            if(structMember.isBitField()) {
               continue;
            }
            if(structMember.getStructMemberOffset() != null) {
               byteOffset = Math.max(byteOffset, structMember.getStructMemberOffset() + SymbolTypeStruct.getMemberSizeBytes(structMember.getType(), structMember.getArraySize(), programScope));
            } else {
               byteOffset += SymbolTypeStruct.getMemberSizeBytes(structMember.getType(), structMember.getArraySize(), programScope);
            }
         }
      }
      return byteOffset;
   }

   @Override
   public String toString(Program program) {
      if(isUnion) {
         return "union-" + getFullName();
      } else {
         return "struct-" + getFullName();
      }
   }
}
