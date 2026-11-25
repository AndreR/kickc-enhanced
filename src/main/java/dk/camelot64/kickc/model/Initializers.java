package dk.camelot64.kickc.model;

import dk.camelot64.kickc.model.statements.StatementSource;
import dk.camelot64.kickc.model.symbols.ArraySpec;
import dk.camelot64.kickc.model.symbols.StructDefinition;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.*;
import dk.camelot64.kickc.model.values.*;

import java.util.*;

/** Utility methods for initializing variables */
public class Initializers {

   /**
    * Create a statement that initializes a variable with the default (zero) value. The statement has to be added to the program by the caller.
    *
    * @param typeSpec The type of the variable
    * @param statementSource The source line
    * @return The new statement
    */
   public static ConstantValue createZeroValue(ValueTypeSpec typeSpec, StatementSource statementSource) {
      if(typeSpec.getType() instanceof SymbolTypeIntegerFixed) {
         // Add an zero value initializer
         return new ConstantInteger(0L, typeSpec.getType());
      } else if(typeSpec.getType().equals(SymbolType.BOOLEAN)) {
         return new ConstantBool(false);
      } else if(typeSpec.getType() instanceof SymbolTypeStruct) {
         // Add an zero-struct initializer
         SymbolTypeStruct typeStruct = (SymbolTypeStruct) typeSpec.getType();
         return new StructZero(typeStruct);
      } else if(typeSpec.getType() instanceof SymbolTypePointer) {
         SymbolTypePointer typePointer = (SymbolTypePointer) typeSpec.getType();
         if(typePointer.getArraySpec() == null) {
            // Add an zero value initializer
            return new ConstantPointer(0L, typePointer.getElementType());
         } else {
            // Add an zero-filled array initializer
            if(typePointer.getArraySpec().getArraySize() == null) {
               throw new CompileError("Array has no declared size.", statementSource);
            }
            return new ConstantArrayFilled(typePointer.getElementType(), typePointer.getArraySpec().getArraySize());
         }
      } else {
         throw new CompileError("Default initializer not implemented for type " + typeSpec.getType().toCDecl(), statementSource);
      }
   }

   /** Specifies type properties for a value. Holds normal type and array specification. */
   public static class ValueTypeSpec {

      SymbolType type;

      public ValueTypeSpec(SymbolType type) {
         this.type = type;
      }

      public SymbolType getType() {
         return type;
      }

   }


   /**
    * Convert as much as possible of the passed value to a constant. Will convert the entire value if possible. If not all sub-values are converted as possible.
    *
    * @param initValue The value to convert
    * @param typeSpec The specified type of the value (including any array properties)
    * @param program The program
    * @param source The statement source (for error messages)
    * @return The constantified value. A {@link ConstantValue} is possible
    */
   public static RValue constantify(RValue initValue, ValueTypeSpec typeSpec, Program program, StatementSource source) {
      // Remove any const/volatile qualifiers
      typeSpec = new ValueTypeSpec(typeSpec.getType().getQualified(false, false));

      if(initValue == null) {
         // Add an zero-value
         initValue = createZeroValue(typeSpec, source);
      } else if(initValue instanceof ForwardVariableRef) {
         // Do not constantify
      } else if(initValue instanceof CastValue) {
         final CastValue castValue = (CastValue) initValue;
         if(castValue.getValue() instanceof ValueList && castValue.getToType() instanceof SymbolTypeStruct) {
            final SymbolType toType = castValue.getToType();
            final RValue constantSub = constantify(castValue.getValue(), new ValueTypeSpec(toType), program, source);
            if(constantSub instanceof ConstantValue) {
               return new ConstantCastValue(toType, (ConstantValue) constantSub);
            }
         }
      } else if(initValue instanceof UnionDesignator) {
         if(!(typeSpec.getType() instanceof SymbolTypeStruct structType)) {
            throw new CompileError("Designated initializer cannot initialize non-struct/union type " + typeSpec.getType(), source);
         }
         StructDefinition sd = structType.getStructDefinition(program.getScope());
         if(sd != null && sd.isUnion()) {
            initValue = constantifyUnion((UnionDesignator) initValue, structType, program, source);
         } else {
            initValue = constantifyStructOrUnion(new ValueList(Collections.singletonList(initValue)), structType, program, source);
         }
      } else if(typeSpec.getType() instanceof SymbolTypeStruct && ((SymbolTypeStruct) typeSpec.getType()).getStructDefinition(program.getScope()) != null && ((SymbolTypeStruct) typeSpec.getType()).getStructDefinition(program.getScope()).isUnion() && !(initValue instanceof ValueList) && !(initValue instanceof ConstantStructValue)) {
         if(initValue instanceof VariableRef) { Variable var = program.getScope().getVariable((VariableRef) initValue); if(var != null && (typeSpec.getType().equals(var.getType()) || SymbolType.VAR.equals(var.getType()))) { return initValue; } }
         StructDefinition structDefinition = ((SymbolTypeStruct) typeSpec.getType()).getStructDefinition(program.getScope());
         Collection<Variable> memberDefinitions = structDefinition.getAllVars(false);
         if(!memberDefinitions.isEmpty()) {
            Variable firstMember = memberDefinitions.iterator().next();
            int unionSize = ((SymbolTypeStruct) typeSpec.getType()).calculateSizeBytes(structDefinition, program.getScope());
            if(initValue instanceof ConstantInteger ci && ci.getFixedPointSuffix() != null) {
               String sfx = ci.getFixedPointSuffix();
               if("f8".equals(sfx) || unionSize == 3 || ("f16".equals(sfx) && unionSize != 4) || ("f24".equals(sfx) && unionSize != 4)) {
                  throw new CompileError("Cannot initialize union with fixed-point literal: suffix 'f8' is 8.8, 'f16' is 16.16, 'f24' is 8.24; target type does not match", source);
               }
            }
            if(unionSize == 3) {
               throw new CompileError("Cannot initialize 24-bit union directly with scalar. Use F16_8() macro or compound literal.", source);
            }
            UnionDesignator autoDesignator = new UnionDesignator(firstMember.getLocalName(), initValue);
            initValue = constantifyUnion(autoDesignator, (SymbolTypeStruct) typeSpec.getType(), program, source);
         }
      } else if(initValue instanceof ValueList) {
         ValueList initList = (ValueList) initValue;
         if(initList.getList().isEmpty()) {
            initValue = createZeroValue(typeSpec, source);
         } else if(typeSpec.getType() instanceof SymbolTypePointer && ((SymbolTypePointer) typeSpec.getType()).getArraySpec() != null) {
            // Type is an array
            initValue = constantifyArray(initList, (SymbolTypePointer) typeSpec.getType(), program, source);
         } else if(typeSpec.getType() instanceof SymbolTypeStruct) {
            // Type is a struct
            initValue = constantifyStructOrUnion(initList, (SymbolTypeStruct) typeSpec.getType(), program, source);
         } else {
            throw new CompileError("Value list cannot initialize type " + typeSpec.getType(), source);
         }
      } else if(SymbolType.isInteger(typeSpec.getType())) {
         if(initValue instanceof ConstantInteger) {
            Long integer = ((ConstantInteger) initValue).getInteger();
            if(typeSpec.getType() instanceof SymbolTypeIntegerFixed) {
               SymbolTypeIntegerFixed typeIntegerFixed = (SymbolTypeIntegerFixed) typeSpec.getType();
               if(!typeIntegerFixed.contains(integer)) {
                  throw new CompileError("Constant init-value has a non-matching type \n type: " + typeSpec.getType().toString() + "\n value: " + initValue.toString(), source);
               }
            }
            initValue = new ConstantInteger(integer, typeSpec.getType());
         } else if(initValue instanceof ConstantValue) {
            SymbolType initValueType = ((ConstantValue) initValue).getType(program.getScope());
            if(!initValueType.equals(typeSpec.getType()))
               initValue = new ConstantCastValue(typeSpec.getType(), (ConstantValue) initValue);
         } else {
            SymbolType inferredType = SymbolTypeInference.inferType(program.getScope(), initValue);
            if(!typeSpec.getType().equals(inferredType) && !SymbolType.VAR.equals(inferredType)) {
               if(SymbolTypeConversion.assignmentTypeMatch(typeSpec.getType(), inferredType))
                  initValue = new CastValue(typeSpec.getType(), initValue);
               else
                  throw new CompileError("Type mismatch (" + typeSpec.getType().toCDecl() + ") cannot be assigned from '" + initValue + "'.", source);
            }
         }
      }
      return initValue;
   }

   /**
    * Convert a union designator initializer to a constant.
    *
    * @param unionInit The union initializer
    * @param structType The union type
    * @param program The program
    * @param source The source line
    * @return The constantified value
    */
   private static RValue constantifyUnion(UnionDesignator unionInit, SymbolTypeStruct structType, Program program, StatementSource source) {
      StructDefinition structDefinition = structType.getStructDefinition(program.getScope());
      Collection<Variable> memberDefinitions = structDefinition.getAllVars(false);

      final String memberName = unionInit.getMemberName();
      final RValue initValue = unionInit.getMemberValue();

      Variable memberDef = null;
      for(Variable definition : memberDefinitions) {
         if(definition.getLocalName().equals(memberName)) {
            memberDef = definition;
         }
      }
      if(memberDef==null)
         throw new CompileError( "Union member not found", source);

      RValue constantifiedMemberValue = constantify(initValue, new ValueTypeSpec(memberDef.getType()), program, source);
      if(constantifiedMemberValue instanceof ConstantValue) {
         LinkedHashMap<SymbolVariableRef, ConstantValue> constMemberMap = new LinkedHashMap<>();
         constMemberMap.put(memberDef.getRef(), (ConstantValue) constantifiedMemberValue);
         return new ConstantStructValue(structType, constMemberMap);
      } else {
         return new CastValue(structType, new ValueList(Collections.singletonList(constantifiedMemberValue)));
      }
   }

   /**
    * Convert as much as possible of a struct or union initializer (positional, designated, or bitfield) to constants.
    *
    * @param valueList The value list
    * @param structType The struct or union type
    * @param program The program
    * @param source The source line
    * @return The constantified value
    */
   private static RValue constantifyStructOrUnion(ValueList valueList, SymbolTypeStruct structType, Program program, StatementSource source) {
      StructDefinition structDefinition = structType.getStructDefinition(program.getScope());
      Collection<Variable> allMembers = structDefinition.getAllVars(false);

      boolean hasDesignators = false;
      for(RValue rv : valueList.getList()) {
         if(rv instanceof UnionDesignator) {
            hasDesignators = true;
            break;
         }
      }

      if(structDefinition.isUnion()) {
         if(hasDesignators) {
            List<UnionDesignator> designators = new ArrayList<>();
            for(RValue rv : valueList.getList()) {
               if(!(rv instanceof UnionDesignator ud)) {
                  throw new CompileError("Cannot mix designated and positional initializers in union " + structType.toCDecl(), source);
               }
               designators.add(ud);
            }
            if(designators.size() == 1) {
               return constantifyUnion(designators.get(0), structType, program, source);
            }
            // Multi-designator union initializer (e.g., anonymous struct flattened into a union):
            // Validate all members exist and check whether their byte ranges are disjoint.
            Map<Variable, RValue> byMember = new LinkedHashMap<>();
            for(UnionDesignator ud : designators) {
               Variable m = structDefinition.getMember(ud.getMemberName());
               if(m == null) {
                  throw new CompileError("Union member '" + ud.getMemberName() + "' not found in " + structType.toCDecl(), source);
               }
               byMember.put(m, ud.getMemberValue());
            }
            List<Variable> orderedMembers = new ArrayList<>(byMember.keySet());
            orderedMembers.sort(Comparator.comparingLong(m -> structDefinition.getMemberByteOffset(m, program.getScope())));
            boolean disjoint = true;
            long prevEnd = 0;
            for(Variable m : orderedMembers) {
               long off = structDefinition.getMemberByteOffset(m, program.getScope());
               long sz = SymbolTypeStruct.getMemberSizeBytes(m.getType(), m.getArraySize(), program.getScope());
               if(off < prevEnd) {
                  disjoint = false;
                  break;
               }
               prevEnd = off + sz;
            }
            if(!disjoint) {
               // Overlapping union alternatives: C99 last designator wins
               return constantifyUnion(designators.get(designators.size() - 1), structType, program, source);
            }
            // Disjoint fields (from flattened anonymous struct inside union):
            // Fill any leading/interior gaps from sibling flattened members with zero values if needed.
            List<Variable> layoutMembers = new ArrayList<>();
            long cursor = 0;
            for(Variable m : orderedMembers) {
               long off = structDefinition.getMemberByteOffset(m, program.getScope());
               if(off > cursor) {
                  for(Variable cand : allMembers) {
                     if(byMember.containsKey(cand) || cand.isBitField() || cand.getStructMemberOffset() == null) continue;
                     long cOff = cand.getStructMemberOffset();
                     long cSz = SymbolTypeStruct.getMemberSizeBytes(cand.getType(), cand.getArraySize(), program.getScope());
                     if(cOff == cursor && cOff + cSz <= off) {
                        layoutMembers.add(cand);
                        cursor += cSz;
                        if(cursor == off) break;
                     }
                  }
               }
               layoutMembers.add(m);
               cursor = off + SymbolTypeStruct.getMemberSizeBytes(m.getType(), m.getArraySize(), program.getScope());
            }
            boolean allConst = true;
            LinkedHashMap<SymbolVariableRef, ConstantValue> constMemberMap = new LinkedHashMap<>();
            List<RValue> designatedRuntimeList = new ArrayList<>();
            for(Variable m : layoutMembers) {
               RValue rawVal = byMember.get(m);
               RValue cVal = constantify(rawVal, new ValueTypeSpec(m.getType()), program, source);
               designatedRuntimeList.add(new UnionDesignator(m.getLocalName(), cVal));
               if(cVal instanceof ConstantValue cv) {
                  constMemberMap.put(m.getRef(), cv);
               } else {
                  allConst = false;
               }
            }
            if(allConst) {
               return new ConstantStructValue(structType, constMemberMap);
            } else {
               return new CastValue(structType, new ValueList(designatedRuntimeList));
            }
         }
         // Positional union initializer: 1 value required
         if(valueList.getList().size() != 1) {
            throw new CompileError(
                  "Union initializer has wrong size. One value is required.\n" +
                        " Union initializer: " + valueList.toString(program),
                  source);
         }
         Variable firstMember = allMembers.iterator().next();
         RValue constantifiedMemberValue = constantify(valueList.getList().get(0), new ValueTypeSpec(firstMember.getType()), program, source);
         if(constantifiedMemberValue instanceof ConstantValue cv) {
            LinkedHashMap<SymbolVariableRef, ConstantValue> constMemberMap = new LinkedHashMap<>();
            constMemberMap.put(firstMember.getRef(), cv);
            return new ConstantStructValue(structType, constMemberMap);
         } else {
            return new CastValue(structType, new ValueList(Collections.singletonList(constantifiedMemberValue)));
         }
      }

      // Struct initializer (KRIT-10 multi-designator + TS-07 bitfield support)
      boolean hasBitfields = structDefinition.hasBitfields();
      List<Variable> storageMembers = new ArrayList<>();
      List<Variable> logicalMembers = new ArrayList<>();
      for(Variable m : allMembers) {
         if(!m.isBitField()) {
            storageMembers.add(m);
         }
         if(!m.getLocalName().startsWith("__bf_")) {
            logicalMembers.add(m);
         }
      }

      Map<String, RValue> logicalValues = new LinkedHashMap<>();
      if(hasDesignators) {
         Set<String> validLogicalNames = new HashSet<>();
         for(Variable lm : logicalMembers) {
            validLogicalNames.add(lm.getLocalName());
         }
         for(RValue rv : valueList.getList()) {
            if(!(rv instanceof UnionDesignator ud)) {
               throw new CompileError("Cannot mix designated and positional initializers in struct " + structType.toCDecl(), source);
            }
            if(!validLogicalNames.contains(ud.getMemberName())) {
               throw new CompileError("Unknown struct member '" + ud.getMemberName() + "' in " + structType.toCDecl(), source);
            }
            logicalValues.put(ud.getMemberName(), ud.getMemberValue());
         }
      } else {
         if(!hasBitfields && logicalMembers.size() != valueList.getList().size()) {
            throw new CompileError(
                  "Struct initializer has wrong size (" + valueList.getList().size() + "), " +
                        "which does not match the number of members in " + structType.toCDecl() + " (" + logicalMembers.size() + " members).\n" +
                        " Struct initializer: " + valueList.toString(program),
                  source);
         }
         if(hasBitfields && valueList.getList().size() > logicalMembers.size()) {
            throw new CompileError(
                  "Struct initializer has too many elements (" + valueList.getList().size() + "), " +
                        "expected at most " + logicalMembers.size() + " for " + structType.toCDecl() + ".\n" +
                        " Struct initializer: " + valueList.toString(program),
                  source);
         }
         for(int i = 0; i < valueList.getList().size(); i++) {
            logicalValues.put(logicalMembers.get(i).getLocalName(), valueList.getList().get(i));
         }
      }

      boolean allConst = true;
      List<RValue> constantifiedList = new ArrayList<>();
      LinkedHashMap<SymbolVariableRef, ConstantValue> constMemberMap = new LinkedHashMap<>();
      for(Variable storageMember : storageMembers) {
         RValue constantifiedMemberValue;
         if(hasBitfields && storageMember.getLocalName().startsWith("__bf_")) {
            long packed = 0L;
            String containerName = storageMember.getLocalName();
            for(Variable bf : logicalMembers) {
               if(!bf.isBitField() || !containerName.equals(bf.getBitFieldContainerName())) continue;
               RValue rawBfVal = logicalValues.get(bf.getLocalName());
               if(rawBfVal == null) continue;
               RValue cBfVal = constantify(rawBfVal, new ValueTypeSpec(bf.getType()), program, source);
               if(!(cBfVal instanceof ConstantValue cv)) {
                  throw new CompileError("Non-constant bitfield initializer for '" + bf.getLocalName() + "' in " + structType.toCDecl() + " is not supported in brace initializers", source);
               }
               ConstantLiteral lit = cv.calculateLiteral(program.getScope());
               if(!(lit instanceof ConstantInteger ci)) {
                  throw new CompileError("Bitfield initializer for '" + bf.getLocalName() + "' must be a constant integer", source);
               }
               int width = bf.getBitFieldWidth();
               int offset = bf.getBitFieldOffset();
               long mask = (1L << width) - 1L;
               packed |= (ci.getInteger() & mask) << offset;
            }
            constantifiedMemberValue = new ConstantInteger(packed, storageMember.getType());
         } else {
            RValue rawVal = logicalValues.get(storageMember.getLocalName());
            constantifiedMemberValue = constantify(rawVal, new ValueTypeSpec(storageMember.getType()), program, source);
         }
         constantifiedList.add(constantifiedMemberValue);
         if(constantifiedMemberValue instanceof ConstantValue cv) {
            constMemberMap.put(storageMember.getRef(), cv);
         } else {
            allConst = false;
         }
      }
      if(allConst) {
         return new ConstantStructValue(structType, constMemberMap);
      } else {
         return new CastValue(structType, new ValueList(constantifiedList));
      }
   }

   /**
    * Convert as much of an array to constant as possible. Also zero-pads the value-list to the declared array length (if possible).
    *
    * @param valueList The list of values
    * @param arrayType The pointer type of the array
    * @param program The program
    * @param source The source line
    * @return The constantified value
    */
   private static RValue constantifyArray(ValueList valueList, SymbolTypePointer arrayType, Program program, StatementSource source) {
      ArraySpec arraySpec = arrayType.getArraySpec();
      SymbolType elementType = arrayType.getElementType();
      // TODO: Handle array of array
      ValueTypeSpec elementTypeSpec = new ValueTypeSpec(elementType);
      boolean allConst = true;
      List<RValue> constantifiedList = new ArrayList<>();
      for(RValue elementValue : valueList.getList()) {
         RValue constantifiedElement = constantify(elementValue, elementTypeSpec, program, source);
         constantifiedList.add(constantifiedElement);
         if(!(constantifiedElement instanceof ConstantValue))
            allConst = false;
      }
      if(allConst) {
         // Convert to ConstantArrayList (throwing away the array size)
         ArrayList<ConstantValue> constElementList = new ArrayList<>();
         for(RValue rValue : constantifiedList) {
            constElementList.add((ConstantValue) rValue);
         }
         return new ConstantArrayList(constElementList, elementType, arraySpec.getArraySize());
      } else {
         // Convert to a ValueList with a cast to the pointer-type (throwing away the array size)
         return new CastValue(arrayType, new ValueList(constantifiedList));
      }
   }

}
