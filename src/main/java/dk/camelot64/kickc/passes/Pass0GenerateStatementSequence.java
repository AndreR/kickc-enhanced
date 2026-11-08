package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.NumberParser;
import dk.camelot64.kickc.SourceLoader;
import dk.camelot64.kickc.model.InternalError;
import dk.camelot64.kickc.model.*;
import dk.camelot64.kickc.model.operators.*;
import dk.camelot64.kickc.model.statements.*;
import dk.camelot64.kickc.model.symbols.*;
import dk.camelot64.kickc.model.types.*;
import dk.camelot64.kickc.model.values.*;
import dk.camelot64.kickc.parser.CParser;
import dk.camelot64.kickc.parser.KickCParser;
import dk.camelot64.kickc.parser.KickCParserBaseVisitor;
import dk.camelot64.kickc.passes.utils.SizeOfConstants;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates program SSA form by visiting the ANTLR4 parse tree
 */
public class Pass0GenerateStatementSequence extends KickCParserBaseVisitor<Object> {

   /** The C parser keeping track of C-files and lexers */
   private final CParser cParser;
   /** The source ANTLR parse tree of the source file. */
   private final KickCParser.FileContext fileCtx;
   /** The program containing all compile structures. */
   private final Program program;
   /** Used to build the scopes of the source file. */
   private final Stack<Scope> scopeStack;
   /** All #pragma constructor_for() statements. Collected during parsing and handled by {@link #generate()} before returning. */
   private final List<KickCParser.PragmaContext> pragmaConstructorFors;


   public Pass0GenerateStatementSequence(CParser cParser, KickCParser.FileContext fileCtx, Program program, Procedure.CallingConvention initialCallingConvention, StringEncoding defaultEncoding, String defaultInterruptType) {
      this.cParser = cParser;
      this.fileCtx = fileCtx;
      this.program = program;
      this.scopeStack = new Stack<>();
      this.currentCallingConvention = initialCallingConvention;
      this.currentEncoding = defaultEncoding;
      this.pragmaConstructorFors = new ArrayList<>();
      this.currentInterruptType = defaultInterruptType;
      scopeStack.push(program.getScope());
   }

   private Scope getCurrentScope() {
      return scopeStack.peek();
   }

   private Procedure getCurrentProcedure() {
      for(Scope scope : scopeStack) {
         if(scope instanceof Procedure) {
            return (Procedure) scope;
         }
      }
      return null;
   }

   /**
    * Get the current procedure compilation
    *
    * @return The current procedure compilation
    */
   private ProcedureCompilation getCurrentProcedureCompilation() {
      final Procedure currentProcedure = getCurrentProcedure();
      if(currentProcedure == null)
         return null;
      else
         return program.getProcedureCompilation(currentProcedure.getRef());
   }

   /**
    * Add an intermediate variable to the current scope.
    * <p>
    * If the current scope is global the variable is added to the _init() scope.
    *
    * @return The new intermediate variable
    */
   private Variable addIntermediateVar() {
      Scope currentScope = getCurrentScope();
      if(currentScope == null || ScopeRef.ROOT.equals(currentScope.getRef())) {
         currentScope = getInitProc();
      }
      return VariableBuilder.createIntermediate(currentScope, SymbolType.VAR, program);
   }

   /**
    * Add a statement to the current procedure.
    */
   void addStatement(Statement statement) {
      ProcedureCompilation procedureCompilation = getCurrentProcedureCompilation();
      if(procedureCompilation == null) {
         Procedure initProc = getInitProc();
         procedureCompilation = program.getProcedureCompilation(initProc.getRef());
      }
      final StatementSequence statementSequence = procedureCompilation.getStatementSequence();
      statementSequence.addStatement(statement);
   }

   Statement getPreviousStatement() {
      ProcedureCompilation procedureCompilation = getCurrentProcedureCompilation();
      if(procedureCompilation == null) {
         Procedure initProc = getInitProc();
         procedureCompilation = program.getProcedureCompilation(initProc.getRef());
      }
      final StatementSequence statementSequence = procedureCompilation.getStatementSequence();
      List<Statement> statements = statementSequence.getStatements();
      if(statements.size() == 0)
         return null;
      else
         return statements.get(statements.size() - 1);
   }


   private Procedure getInitProc() {
      // Statement outside procedure declaration - put into the _init procedure
      Procedure initProc = program.getScope().getLocalProcedure(SymbolRef.INIT_PROC_NAME);
      if(initProc == null) {
         // Create the _init() procedure
         initProc = new Procedure(SymbolRef.INIT_PROC_NAME, new SymbolTypeProcedure(SymbolType.VOID, new ArrayList<>()), program.getScope(), Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT, Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
         initProc.setDeclaredInline(true);
         initProc.setParameters(new ArrayList<>());
         program.getScope().add(initProc);
         program.createProcedureCompilation(initProc.getRef());
         program.getProcedureCompilation(initProc.getRef()).getStatementSequence().addStatement(new StatementProcedureBegin(initProc.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));
      }
      return initProc;
   }

   public void generate() {
      this.visit(fileCtx);

      // TODO: Handle all forward references here?

      // Handle #pragma constructor_for()
      List<ProcedureRef> constructorProcs = new ArrayList<>();
      for(KickCParser.PragmaContext pragmaConstructorFor : pragmaConstructorFors) {
         final List<KickCParser.PragmaParamContext> names = pragmaConstructorFor.pragmaParam();
         if(names.size() < 2)
            throw new CompileError("#pragma constructor_for requires at least 2 parameters.", new StatementSource(pragmaConstructorFor));
         final String constructorProcName = pragmaParamName(names.get(0));
         final Procedure constructorProc = program.getScope().getLocalProcedure(constructorProcName);
         if(constructorProc == null)
            throw new CompileError("Constructor procedure not found " + constructorProcName, new StatementSource(pragmaConstructorFor));
         for(int i = 1; i < names.size(); i++) {
            final String procName = pragmaParamName(names.get(i));
            final Procedure proc = program.getScope().getLocalProcedure(procName);
            if(proc == null)
               throw new CompileError("Procedure not found " + procName, new StatementSource(pragmaConstructorFor));
            if(program.getLog().isVerboseParse())
               program.getLog().append("Added constructor procedure " + constructorProc.getRef().toString() + " to procedure " + proc.getRef().toString());
            proc.getConstructorRefs().add(constructorProc.getRef());
         }
         if(!constructorProcs.contains(constructorProc.getRef())) {
            constructorProcs.add(constructorProc.getRef());
            // Add call to constructor procedure to the __init() procedure
            addStatement(new StatementCall(null, constructorProc.getLocalName(), new ArrayList<>(), new StatementSource(pragmaConstructorFor), Comment.NO_COMMENTS));
            // Mark the constructor procedure
            constructorProc.setConstructor(true);
         }
      }

      // Finalize the _init() procedure - if present
      final ProcedureRef initProcedureRef = new ProcedureRef(SymbolRef.INIT_PROC_NAME);
      final ProcedureCompilation initCompilation = program.getProcedureCompilation(initProcedureRef);
      if(initCompilation != null) {
         final StatementSequence initSequence = initCompilation.getStatementSequence();
         final Label initReturnLabel = program.getScope().getProcedure(initProcedureRef).addLabel(SymbolRef.PROCEXIT_BLOCK_NAME);
         initSequence.addStatement(new StatementLabel(initReturnLabel.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));
         initSequence.addStatement(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
         initSequence.addStatement(new StatementProcedureEnd(initProcedureRef, StatementSource.NONE, Comment.NO_COMMENTS));
      }

      // Add the _start() procedure to the program
      {
         program.setStartProcedure(new ProcedureRef(SymbolRef.START_PROC_NAME));
         final Procedure startProcedure = new Procedure(SymbolRef.START_PROC_NAME, new SymbolTypeProcedure(SymbolType.VOID, new ArrayList<>()), program.getScope(), Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT, Procedure.CallingConvention.PHI_CALL, Bank.COMMON);
         startProcedure.setParameters(new ArrayList<>());
         program.getScope().add(startProcedure);
         final ProcedureCompilation startProcedureCompilation = program.createProcedureCompilation(startProcedure.getRef());
         final StatementSequence startSequence = startProcedureCompilation.getStatementSequence();
         startSequence.addStatement(new StatementProcedureBegin(startProcedure.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));
         if(initCompilation != null)
            startSequence.addStatement(new StatementCall(null, SymbolRef.INIT_PROC_NAME, new ArrayList<>(), StatementSource.NONE, Comment.NO_COMMENTS));
         final Procedure mainProc = program.getScope().getLocalProcedure(SymbolRef.MAIN_PROC_NAME);
         if(mainProc == null)
            throw new CompileError("Required main() not defined in program.");
         if(!SymbolType.VOID.equals(mainProc.getReturnType()) && !SymbolType.SWORD.equals(mainProc.getReturnType()))
            throw new CompileError("return of main() must be 'void' or of type 'int'.", mainProc.getDefinitionSource());
         if(mainProc.getParameterNames().size() == 0) {
            startSequence.addStatement(new StatementCall(null, SymbolRef.MAIN_PROC_NAME, new ArrayList<>(), StatementSource.NONE, Comment.NO_COMMENTS));
         } else if(mainProc.getParameterNames().size() == 2) {
            final List<Variable> parameters = mainProc.getParameters();
            final Variable argc = parameters.get(0);
            if(!SymbolType.SWORD.equals(argc.getType()))
               throw new CompileError("first parameter of main() must be of type 'int'.", mainProc.getDefinitionSource());
            final Variable argv = parameters.get(1);
            if(!argv.getType().equals(new SymbolTypePointer(new SymbolTypePointer(SymbolType.BYTE))))
               throw new CompileError("second parameter of main() must be of type 'char **'.", mainProc.getDefinitionSource());
            final ArrayList<RValue> params = new ArrayList<>();
            params.add(new ConstantInteger(0L, SymbolType.SWORD));
            params.add(new ConstantPointer(0L, new SymbolTypePointer(SymbolType.BYTE)));
            startSequence.addStatement(new StatementCall(null, SymbolRef.MAIN_PROC_NAME, params, StatementSource.NONE, Comment.NO_COMMENTS));
         } else
            throw new CompileError("main() has wrong number of parameters. It must have zero or 2 parameters.", mainProc.getDefinitionSource());


         final Label startReturnLabel = startProcedure.addLabel(SymbolRef.PROCEXIT_BLOCK_NAME);
         startSequence.addStatement(new StatementLabel(startReturnLabel.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));
         startSequence.addStatement(new StatementReturn(null, StatementSource.NONE, Comment.NO_COMMENTS));
         startSequence.addStatement(new StatementProcedureEnd(startProcedure.getRef(), StatementSource.NONE, Comment.NO_COMMENTS));
      }

   }

   @Override
   public Void visitFile(KickCParser.FileContext ctx) {
      if(program.getMainFileComments() == null) {
         // Only set program file level comments for the first file.
         program.setMainFileComments(ensureUnusedComments(getCommentsFile(ctx)));
      }
      this.visit(ctx.declSeq());
      return null;
   }

   @Override
   public Object visitPragma(KickCParser.PragmaContext ctx) {
      final String pragmaName = ctx.NAME().getText();
      switch(pragmaName) {
         case CParser.PRAGMA_TARGET ->
               throw new InternalError("Error! #pragma target() should be handled in preprocessor!");
         case CParser.PRAGMA_CPU -> {
            final String cpuName = pragmaParamName(pragmaParamSingle(ctx));
            TargetCpu cpu = TargetCpu.getTargetCpu(cpuName, false);
            program.getTargetPlatform().setCpu(cpu);
         }
         case CParser.PRAGMA_VAR_MODEL -> {
            final List<KickCParser.PragmaParamContext> pragmaParams = ctx.pragmaParam();
            List<String> settings = pragmaParams.stream().map(Pass0GenerateStatementSequence::pragmaParamName).collect(Collectors.toList());
            final VariableBuilderConfig config = VariableBuilderConfig.fromSettings(settings, new StatementSource(ctx));
            config.setStructModelClassic(program.getTargetPlatform().getVariableBuilderConfig().isStructModelClassic());
            program.getTargetPlatform().setVariableBuilderConfig(config);
         }
         case CParser.PRAGMA_STRUCT_MODEL -> {
            final String modelName = pragmaParamName(pragmaParamSingle(ctx));
            if(modelName.equalsIgnoreCase("classic"))
               program.getTargetPlatform().getVariableBuilderConfig().setStructModelClassic(true);
            else if(modelName.equalsIgnoreCase("unwind"))
               program.getTargetPlatform().getVariableBuilderConfig().setStructModelClassic(true);
            else
               throw new CompileError("Unknown struct model " + modelName, new StatementSource(ctx));
         }
         case CParser.PRAGMA_LINKSCRIPT -> {
            final String linkScriptName = pragmaParamString(pragmaParamSingle(ctx));
            program.getLog().append("Loading link script \"" + linkScriptName + "\"");
            Path currentPath = cParser.getSourceFolderPath(ctx);
            final File linkScriptFile = SourceLoader.loadFile(linkScriptName, currentPath, program.getTargetPlatformPaths());
            program.getTargetPlatform().setLinkScriptFile(linkScriptFile);
         }
         case CParser.PRAGMA_EXTENSION -> {
            String extension = pragmaParamString(pragmaParamSingle(ctx));
            program.getTargetPlatform().setOutFileExtension(extension);
            program.getOutputFileManager().setBinaryExtension(extension);
         }
         case CParser.PRAGMA_EMULATOR -> {
            String emuName = pragmaParamString(pragmaParamSingle(ctx));
            program.getTargetPlatform().setEmulatorCommand(emuName);
         }
         case CParser.PRAGMA_ENCODING -> {
            final String encodingName = pragmaParamName(pragmaParamSingle(ctx));
            try {
               this.currentEncoding = StringEncoding.fromName(encodingName);
            } catch(IllegalArgumentException e) {
               throw new CompileError("Unknown string encoding " + encodingName, new StatementSource(ctx));
            }
         }
         case CParser.PRAGMA_CHARMAP, CParser.PRAGMA_ENCODING_DEF, CParser.PRAGMA_ENCODING_MAP -> {
            handlePragmaCharmap(ctx);
         }
         case CParser.PRAGMA_CODE_SEG -> this.currentSegmentCode = pragmaParamName(pragmaParamSingle(ctx));
         case CParser.PRAGMA_DATA_SEG -> this.currentSegmentData = pragmaParamName(pragmaParamSingle(ctx));
         case CParser.PRAGMA_SEGMENT_TYPE -> {
            if(ctx.pragmaParam().size() < 2)
               throw new CompileError("#pragma segment_type expects at least two parameters: (segmentName, type [, id])", new StatementSource(ctx));
            String segName = pragmaParamName(ctx.pragmaParam(0));
            String typeStr = pragmaParamName(ctx.pragmaParam(1)).toUpperCase();
            int id = -1;
            if(ctx.pragmaParam().size() >= 3) {
               id = pragmaParamNumber(ctx.pragmaParam(2)).intValue();
            }
            SegmentTypeInfo.Kind kind;
            switch(typeStr) {
               case "ROM" -> kind = SegmentTypeInfo.Kind.ROM;
               case "ROM_BANK" -> kind = SegmentTypeInfo.Kind.ROM_BANK;
               case "RAM_RESIDENT", "RAM" -> kind = SegmentTypeInfo.Kind.RAM_RESIDENT;
               case "RAM_OVERLAY" -> kind = SegmentTypeInfo.Kind.RAM_OVERLAY;
               default -> throw new CompileError("Unknown segment type '" + typeStr + "'. Expected ROM, ROM_BANK, RAM_RESIDENT or RAM_OVERLAY", new StatementSource(ctx));
            }
            program.setSegmentType(segName, new SegmentTypeInfo(kind, id));
         }
         case CParser.PRAGMA_LOOP_ALIGN -> {
            if(ctx.pragmaParam().isEmpty()) {
               this.program.setLoopAlignThreshold(32);
            } else {
               KickCParser.PragmaParamContext param = ctx.pragmaParam(0);
               if(param instanceof KickCParser.PragmaParamNumberContext) {
                  this.program.setLoopAlignThreshold(pragmaParamNumber(param).intValue());
               } else {
                  String val = pragmaParamName(param).toLowerCase();
                  if("on".equals(val) || "true".equals(val) || "yes".equals(val)) {
                     this.program.setLoopAlignThreshold(32);
                  } else if("off".equals(val) || "false".equals(val) || "no".equals(val)) {
                     this.program.setLoopAlignThreshold(0);
                  } else {
                     try {
                        this.program.setLoopAlignThreshold(Integer.parseInt(val));
                     } catch(NumberFormatException e) {
                        throw new CompileError("Expected number or on/off for #pragma loop_align", new StatementSource(ctx));
                     }
                  }
               }
            }
         }
         case CParser.PRAGMA_BANK -> {
            if(ctx.pragmaParam().size() != 2)
               throw new CompileError("#pragma expects two parameters!", new StatementSource(ctx));
            try {
               final String pragmaBankArea = pragmaParamName(ctx.pragmaParam(0));
               final Number pragmaBank = pragmaParamNumber(ctx.pragmaParam(1));
               this.currentBank = new Bank(pragmaBankArea, pragmaBank.longValue());
            } catch(IllegalArgumentException e) {
               throw new CompileError("Illegal bank parameter " + ctx.getText(), new StatementSource(ctx));
            }
         }
         case CParser.PRAGMA_NOBANK -> this.currentBank = Bank.COMMON; // When the current segment is null, the procedure will not be declared as far.
         case CParser.PRAGMA_RESOURCE -> {
            String resourceFileName = pragmaParamString(pragmaParamSingle(ctx));
            addResourceFile(ctx, resourceFileName);
         }
         case CParser.PRAGMA_START_ADDRESS -> {
            Number startAddress = pragmaParamNumber(pragmaParamSingle(ctx));
            program.getTargetPlatform().setStartAddress(startAddress);
         }
         case CParser.PRAGMA_CALLING -> currentCallingConvention = pragmaParamCallingConvention(pragmaParamSingle(ctx));
         case CParser.PRAGMA_INTERRUPT -> this.currentInterruptType = pragmaParamName(pragmaParamSingle(ctx));
         case CParser.PRAGMA_ZP_RESERVE -> {
            List<Integer> reservedZps = pragmaParamRanges(ctx.pragmaParam());
            program.addReservedZps(reservedZps);
         }
         case CParser.PRAGMA_CONSTRUCTOR_FOR -> {
            this.pragmaConstructorFors.add(ctx);
            return null;
         }
         default -> program.getLog().append("Warning! Unknown #pragma " + pragmaName);
      }
      return null;
   }

   /**
    * Check that a #pragma has a single parameter - and return that parameter
    *
    * @param ctx The #pragma
    * @return The single parameter
    */
   private static KickCParser.PragmaParamContext pragmaParamSingle(KickCParser.PragmaContext ctx) {
      if(ctx.pragmaParam().size() != 1)
         throw new CompileError("#pragma expects a single parameter!", new StatementSource(ctx));
      return ctx.pragmaParam().get(0);
   }

   /**
    * Parse a single NUMBER parameter of a #pragma
    * If the parameter is not a NUMBER the compiler will fail out
    *
    * @param paramCtx The parameter to parse
    * @return The number
    */
   private static Number pragmaParamNumber(KickCParser.PragmaParamContext paramCtx) {
      if(!(paramCtx instanceof KickCParser.PragmaParamNumberContext))
         throw new CompileError("Expected a NUMBER parameter. Found '" + paramCtx.getText() + "'.", new StatementSource(paramCtx.getParent()));
      final Number number = NumberParser.parseLiteral(((KickCParser.PragmaParamNumberContext) paramCtx).NUMBER().getText());
      if(number == null)
         throw new CompileError("Expected a NUMBER parameter. Found '" + paramCtx.getText() + "'.", new StatementSource(paramCtx.getParent()));
      return number;
   }

   /**
    * Parse a single CALLINGCONVENTION parameter of a #pragma
    * If the parameter is not a CALLINGCONVENTION the compiler will fail out
    *
    * @param paramCtx The parameter to parse
    * @return The name
    */
   private static Procedure.CallingConvention pragmaParamCallingConvention(KickCParser.PragmaParamContext paramCtx) {
      if(!(paramCtx instanceof KickCParser.PragmaParamCallingConventionContext))
         throw new CompileError("Expected a CALLINGCONVENTION parameter. Found '" + paramCtx.getText() + "'.", new StatementSource(paramCtx.getParent()));
      final String callingConventionName = ((KickCParser.PragmaParamCallingConventionContext) paramCtx).CALLINGCONVENTION().getText();
      final Procedure.CallingConvention callingConvention = Procedure.CallingConvention.getCallingConvension(callingConventionName);
      if(callingConvention == null)
         throw new CompileError("Expected a CALLINGCONVENTION parameter. Found '" + paramCtx.getText() + "'.", new StatementSource(paramCtx.getParent()));
      return callingConvention;
   }


   /**
    * Parse a single NAME parameter of a #pragma
    * If the parameter is not a NAME the compiler will fail out
    *
    * @param paramCtx The parameter to parse
    * @return The name
    */
   private static String pragmaParamName(KickCParser.PragmaParamContext paramCtx) {
      if(!(paramCtx instanceof KickCParser.PragmaParamNameContext))
         throw new CompileError("Expected a NAME parameter. Found '" + paramCtx.getText() + "'.", new StatementSource(paramCtx.getParent()));
      return ((KickCParser.PragmaParamNameContext) paramCtx).NAME().getText();
   }

   /**
    * Parse a single STRING parameter of a #pragma
    * If the parameter is not a STRING the compiler will fail out
    *
    * @param paramCtx The parameter to parse
    * @return The string
    */
   private static String pragmaParamString(KickCParser.PragmaParamContext paramCtx) {
      if(!(paramCtx instanceof KickCParser.PragmaParamStringContext))
         throw new CompileError("Expected a STRING parameter. Found '" + paramCtx.getText() + "'.", new StatementSource(paramCtx.getParent()));
      final String stringLiteral = ((KickCParser.PragmaParamStringContext) paramCtx).STRING().getText();
      int lastQuote = stringLiteral.lastIndexOf('"');
      return (lastQuote > 0) ? stringLiteral.substring(1, lastQuote) : stringLiteral.substring(1, stringLiteral.length() - 1);
   }

   private void handlePragmaCharmap(KickCParser.PragmaContext ctx) {
      List<KickCParser.PragmaParamContext> params = ctx.pragmaParam();
      if(params.isEmpty()) {
         throw new CompileError("#pragma " + ctx.NAME().getText() + " requires at least an encoding name and mapping parameters!", new StatementSource(ctx));
      }
      String encodingName = pragmaParamName(params.get(0));
      StringEncoding baseEncoding = this.currentEncoding;
      int mappingStartIndex = 1;

      if(params.size() > 1 && params.get(1) instanceof KickCParser.PragmaParamNameContext) {
         String baseName = pragmaParamName(params.get(1));
         try {
            baseEncoding = StringEncoding.fromName(baseName);
         } catch(IllegalArgumentException e) {
            throw new CompileError("Unknown base encoding '" + baseName + "' in #pragma " + ctx.NAME().getText(), new StatementSource(ctx));
         }
         mappingStartIndex = 2;
      }

      Map<Character, Byte> customMappings = new LinkedHashMap<>();
      for(int i = mappingStartIndex; i < params.size(); i++) {
         KickCParser.PragmaParamContext paramCtx = params.get(i);
         if(paramCtx instanceof KickCParser.PragmaParamStringContext) {
            String mappingStr = pragmaParamString(paramCtx);
            parseCharmapString(mappingStr, customMappings, new StatementSource(paramCtx));
         } else {
            throw new CompileError("Expected mapping string in #pragma " + ctx.NAME().getText() + ", found: " + paramCtx.getText(), new StatementSource(paramCtx));
         }
      }

      StringEncoding.createCustom(encodingName, baseEncoding, encodingName, customMappings);
   }

   private void parseCharmapString(String mappingStr, Map<Character, Byte> mappings, StatementSource source) {
      int len = mappingStr.length();
      int i = 0;
      while(i < len) {
         while(i < len && (Character.isWhitespace(mappingStr.charAt(i)) || mappingStr.charAt(i) == ',' || mappingStr.charAt(i) == ';')) {
            i++;
         }
         if(i >= len) break;

         char keyChar;
         char c = mappingStr.charAt(i);
         if(c == '\'' || c == '"') {
            char quote = c;
            i++;
            if(i >= len) throw new CompileError("Unclosed quote in charmap key: " + mappingStr, source);
            if(mappingStr.charAt(i) == '\\') {
               i++;
               keyChar = parseEscapeChar(mappingStr, i, source);
               i += getEscapeLength(mappingStr, i);
            } else {
               keyChar = mappingStr.charAt(i);
               i++;
            }
            if(i < len && mappingStr.charAt(i) == quote) {
               i++;
            }
         } else if(c == '\\') {
            i++;
            keyChar = parseEscapeChar(mappingStr, i, source);
            i += getEscapeLength(mappingStr, i);
         } else {
            keyChar = c;
            i++;
         }

         while(i < len && Character.isWhitespace(mappingStr.charAt(i))) {
            i++;
         }

         if(i >= len || (mappingStr.charAt(i) != '=' && mappingStr.charAt(i) != ':')) {
            throw new CompileError("Expected '=' or ':' after character '" + keyChar + "' in charmap: " + mappingStr, source);
         }
         i++;

         while(i < len && Character.isWhitespace(mappingStr.charAt(i))) {
            i++;
         }
         if(i >= len) {
            throw new CompileError("Missing value for character '" + keyChar + "' in charmap: " + mappingStr, source);
         }

         int valStart = i;
         if(mappingStr.charAt(i) == '\'' || mappingStr.charAt(i) == '"') {
            char quote = mappingStr.charAt(i);
            i++;
            char valChar;
            if(mappingStr.charAt(i) == '\\') {
               i++;
               valChar = parseEscapeChar(mappingStr, i, source);
               i += getEscapeLength(mappingStr, i);
            } else {
               valChar = mappingStr.charAt(i);
               i++;
            }
            if(i < len && mappingStr.charAt(i) == quote) {
               i++;
            }
            mappings.put(keyChar, (byte) valChar);
         } else {
            while(i < len && !Character.isWhitespace(mappingStr.charAt(i)) && mappingStr.charAt(i) != ',' && mappingStr.charAt(i) != ';') {
               i++;
            }
            String valStr = mappingStr.substring(valStart, i);
            int intVal;
            try {
               if(valStr.startsWith("0x") || valStr.startsWith("0X")) {
                  intVal = Integer.parseInt(valStr.substring(2), 16);
               } else if(valStr.startsWith("$")) {
                  intVal = Integer.parseInt(valStr.substring(1), 16);
               } else if(valStr.startsWith("%")) {
                  intVal = Integer.parseInt(valStr.substring(1), 2);
               } else if(valStr.startsWith("0b") || valStr.startsWith("0B")) {
                  intVal = Integer.parseInt(valStr.substring(2), 2);
               } else {
                  intVal = Integer.parseInt(valStr);
               }
            } catch(NumberFormatException e) {
               throw new CompileError("Invalid number format '" + valStr + "' in charmap: " + mappingStr, source);
            }
            if(intVal < -128 || intVal > 255) {
               throw new CompileError("Value " + intVal + " out of byte range (-128..255) for character '" + keyChar + "' in charmap", source);
            }
            mappings.put(keyChar, (byte) intVal);
         }
      }
   }

   private char parseEscapeChar(String str, int index, StatementSource source) {
      if(index >= str.length()) throw new CompileError("Incomplete escape sequence in charmap", source);
      char esc = str.charAt(index);
      return switch(esc) {
         case 'n' -> '\n';
         case 'r' -> '\r';
         case 't' -> '\t';
         case 'f' -> '\f';
         case '0' -> '\0';
         case '\'' -> '\'';
         case '"' -> '"';
         case '\\' -> '\\';
         case 'x' -> {
            if(index + 2 >= str.length()) throw new CompileError("Incomplete hex escape in charmap", source);
            yield (char) Integer.parseInt(str.substring(index + 1, index + 3), 16);
         }
         default -> esc;
      };
   }

   private int getEscapeLength(String str, int index) {
      if(index < str.length() && str.charAt(index) == 'x') {
         return 3;
      }
      return 1;
   }

   /**
    * Find a reserved ZP-addresses from a list of pragma parameters (consisting of numbers and number ranges).
    *
    * @param reserveParams The params
    * @return The list of reserved zeropage addresses
    */
   private List<Integer> pragmaParamRanges(List<KickCParser.PragmaParamContext> reserveParams) {
      List<Integer> reservedZps = new ArrayList<>();
      for(KickCParser.PragmaParamContext reserveCtx : reserveParams) {
         if(reserveCtx instanceof KickCParser.PragmaParamNumberContext) {
            final TerminalNode number = ((KickCParser.PragmaParamNumberContext) reserveCtx).NUMBER();
            // Only a single reserved address
            Number reservedZp = NumberParser.parseLiteral(number.getText());
            reservedZps.add(reservedZp.intValue());
         } else if(reserveCtx instanceof KickCParser.PragmaParamRangeContext) {
            final TerminalNode rangeStart = ((KickCParser.PragmaParamRangeContext) reserveCtx).NUMBER(0);
            final TerminalNode rangeEnd = ((KickCParser.PragmaParamRangeContext) reserveCtx).NUMBER(1);
            // A range of reserved addresses
            Number startZp = NumberParser.parseLiteral(rangeStart.getText());
            Number endZp = NumberParser.parseLiteral(rangeEnd.getText());
            int zp = startZp.intValue();
            while(zp <= endZp.intValue()) {
               reservedZps.add(zp);
               zp++;
            }
         } else {
            throw new CompileError("Expected a NUMBER or RANGE parameter. Found '" + reserveCtx.getText() + "'.", new StatementSource(reserveCtx.getParent()));
         }
      }
      return reservedZps;
   }

   /** The current calling convention for procedures. */
   private Procedure.CallingConvention currentCallingConvention;

   /** The current code segment. */
   private String currentSegmentCode = Scope.SEGMENT_CODE_DEFAULT;

   /** The current data segment. */
   private String currentSegmentData = Scope.SEGMENT_DATA_DEFAULT;

   /** The current far segment. If null, the sequent procedures won't be banked. */
   private Bank currentBank = Bank.COMMON;

   /** The current default interrupt type. */
   private String currentInterruptType;

   @Override
   public Object visitDeclFunction(KickCParser.DeclFunctionContext ctx) {
      this.visit(ctx.declType());
      this.visit(ctx.declarator());

      // Declare & define the procedure
      Procedure procedure = declareProcedure(true, ctx, StatementSource.procedureDecl(ctx));

      varDecl.exitType();

      // enter the procedure
      scopeStack.push(procedure);

      Variable returnVar = procedure.getLocalVar("return");

      // Add the body
      addStatement(new StatementProcedureBegin(procedure.getRef(), StatementSource.procedureBegin(ctx), Comment.NO_COMMENTS));

      Label procExit = procedure.addLabel(SymbolRef.PROCEXIT_BLOCK_NAME);
      if(ctx.stmtSeq() != null) {
         this.visit(ctx.stmtSeq());
      }
      addStatement(new StatementLabel(procExit.getRef(), StatementSource.procedureEnd(ctx), Comment.NO_COMMENTS));
      if(Procedure.CallingConvention.PHI_CALL.equals(procedure.getCallingConvention()) && returnVar != null && returnVar.isKindPhiMaster()) {
         addStatement(new StatementAssignment(returnVar.getVariableRef(), returnVar.getRef(), false, StatementSource.procedureEnd(ctx), Comment.NO_COMMENTS));
      }
      SymbolVariableRef returnVarRef = null;
      if(returnVar != null) {
         returnVarRef = returnVar.getRef();
      }
      addStatement(new StatementReturn(returnVarRef, StatementSource.procedureEnd(ctx), Comment.NO_COMMENTS));
      addStatement(new StatementProcedureEnd(procedure.getRef(), StatementSource.procedureEnd(ctx), Comment.NO_COMMENTS));

      // exit the procedure
      scopeStack.pop();

      return null;
   }


   /**
    * Declare a procedure (either as part of a forward declaration or as part of a definition.)
    * Finds the name, type and parameters in the varDecl.
    * If the procedure is already declared then it is checked that the current declaration matches the existing one - and the existing one is returned.
    *
    * @param defineProcedure If true the procedure parameter and return variables will also be defined
    * @param ctx The parser context (used to find any comments.)
    * @param statementSource The statements source (used when producing errors.
    * @return The declared procedure.
    */
   private Procedure declareProcedure(boolean defineProcedure, ParserRuleContext ctx, StatementSource statementSource) {

      boolean isLibrary = program.isLibraryFile(statementSource != null ? statementSource.getFileName() : null);
      String segCode = isLibrary ? Scope.SEGMENT_CODE_DEFAULT : currentSegmentCode;
      String segData = isLibrary ? Scope.SEGMENT_DATA_DEFAULT : currentSegmentData;
      if(program.isSegmentRom(segData)) {
         segData = Scope.SEGMENT_DATA_DEFAULT;
      }
      Bank bank = isLibrary ? Bank.COMMON : currentBank;

      Procedure procedure = new Procedure(varDecl.getVarName(), (SymbolTypeProcedure) varDecl.getEffectiveType(), program.getScope(), segCode, segData, currentCallingConvention, bank);
      addDirectives(procedure, varDecl.getDeclDirectives());
      // Check if the declaration matches any existing declaration!
      final Symbol existingSymbol = program.getScope().getSymbol(procedure.getRef());
      if(existingSymbol != null) {
         // Already declared  - check equality
         if(!(existingSymbol instanceof Procedure) || !SymbolTypeConversion.procedureDeclarationMatch((Procedure) existingSymbol, procedure))
            throw new CompileError("Conflicting declarations for procedure: " + procedure.getFullName(), statementSource);

         if(defineProcedure) {
            // Check that the procedure is not already defined
            Procedure existingProcedure = (Procedure) existingSymbol;
            if(existingProcedure.isDeclaredIntrinsic())
               throw new CompileError("Redefinition of procedure: " + procedure.getFullName(), statementSource);
            final StatementSequence statementSequence = program.getProcedureCompilation(existingProcedure.getRef()).getStatementSequence();
            if(statementSequence != null && statementSequence.getStatements().size() > 0)
               throw new CompileError("Redefinition of procedure " + procedure.getFullName(), statementSource);
         }

         procedure = (Procedure) existingSymbol;
      } else {
         program.getScope().add(procedure);
         program.createProcedureCompilation(procedure.getRef());
      }

      if(defineProcedure) {
         // Make sure comments, directives and source are from the definition
         addDirectives(procedure, varDecl.getDeclDirectives());
         procedure.setComments(ensureUnusedComments(getCommentsSymbol(ctx)));
         procedure.setDefinitionSource(statementSource);
         // enter the procedure
         scopeStack.push(procedure);
         // Add parameter variables...
         boolean variableLengthParameterList = false;
         List<Variable> parameterList = new ArrayList<>();
         for(ParameterDecl parameter : varDecl.parameters) {
            // Handle variable length parameter lists
            if(SymbolType.PARAM_LIST.equals(parameter.type)) {
               procedure.setVariableLengthParameterList(true);
               variableLengthParameterList = true;
               continue;
            } else if(variableLengthParameterList)
               throw new CompileError("Variable length parameter list is only legal as the last parameter.", statementSource);

            // Handle stray void parameters (Any single void parameter was removed by the type parser)
            if(SymbolType.VOID.equals(parameter.type))
               throw new CompileError("Illegal void parameter.", statementSource);

            // Handle parameters without a name in the declaration
            if(parameter.name == null)
               throw new CompileError("Illegal unnamed parameter.", statementSource);

            VariableBuilder varBuilder = new VariableBuilder(parameter.name, getCurrentScope(), true, false, parameter.type, null, segData, program.getTargetPlatform().getVariableBuilderConfig());
            final Variable paramVar = varBuilder.build();
            parameterList.add(paramVar);
         }
         procedure.setParameters(parameterList);
         procedure.setSegmentData(segData);
         procedure.setSegmentCode(segCode);
         if(procedure.getBank().isCommon() || isLibrary) {
            procedure.setBank(bank);
         }
         // Add return variable
         if(!SymbolType.VOID.equals(procedure.getReturnType())) {
            final VariableBuilder builder = new VariableBuilder("return", procedure, false, false, procedure.getReturnType(), varDecl.getDeclDirectives(), segData, program.getTargetPlatform().getVariableBuilderConfig());
            builder.build();
         }
         // exit the procedure
         scopeStack.pop();
      }

      return procedure;
   }

   @Override
   public Object visitParameterDeclTypeDeclarator(KickCParser.ParameterDeclTypeDeclaratorContext ctx) {
      this.visit(ctx.declType());
      this.visit(ctx.declarator());
      ParameterDecl paramDecl = new ParameterDecl(varDecl.getVarName(), varDecl.getEffectiveType());
      varDecl.exitType();
      return paramDecl;
   }

   @Override
   public Object visitParameterDeclTypeName(KickCParser.ParameterDeclTypeNameContext ctx) {
      SymbolType paramType = (SymbolType) this.visit(ctx.typeName());
      return new ParameterDecl(null, paramType);
   }

   @Override
   public Object visitParameterDeclList(KickCParser.ParameterDeclListContext ctx) {
      return new ParameterDecl(null, SymbolType.PARAM_LIST);
   }

   @Override
   public Object visitStmtDeclKasm(KickCParser.StmtDeclKasmContext ctx) {
      final KickAsm kickAsm = (KickAsm) this.visit(ctx.kasmContent());
      StatementKickAsm statementKickAsm = new StatementKickAsm(kickAsm.kickAsmCode, kickAsm.bytes, kickAsm.cycles, kickAsm.uses, kickAsm.declaredClobber, StatementSource.kickAsm(ctx.kasmContent()), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(statementKickAsm);
      return statementKickAsm;
   }

   /** Inline KickAssembler (can be either inline code or inline data initialization). */
   static class KickAsm {
      /** KickAssembler code. */
      private final String kickAsmCode;
      /** Variables/constants used by the kickasm code. */
      private final List<SymbolRef> uses;
      /** The number of bytes generated by the kick-assembler code. */
      private RValue bytes;
      /** The number of cycles used by the generated kick-assembler code. */
      private RValue cycles;
      /** Declared clobber for the inline kick-assembler . */
      private CpuClobber declaredClobber;

      public KickAsm(String kickAsmCode) {
         this.kickAsmCode = kickAsmCode;
         this.uses = new ArrayList<>();
      }
   }

   @Override
   public Object visitKasmContent(KickCParser.KasmContentContext ctx) {
      String kasmBody = ctx.KICKASM_BODY().getText();
      Pattern p = Pattern.compile("\\{\\{[\\s]*(.*)[\\s]*\\}\\}", Pattern.DOTALL);
      Matcher m = p.matcher(kasmBody);
      if(m.find()) {
         String kickAsmCode = m.group(1).replaceAll("\r", "");
         KickAsm kickAsm = new KickAsm(kickAsmCode);
         if(ctx.asmDirectives() != null) {
            List<AsmDirective> asmDirectives = this.visitAsmDirectives(ctx.asmDirectives());
            for(AsmDirective asmDirective : asmDirectives) {
               if(asmDirective instanceof AsmDirectiveBytes) {
                  kickAsm.bytes = ((AsmDirectiveBytes) asmDirective).getBytes();
               } else if(asmDirective instanceof AsmDirectiveCycles) {
                  kickAsm.cycles = ((AsmDirectiveCycles) asmDirective).getCycles();
               } else if(asmDirective instanceof AsmDirectiveUses) {
                  kickAsm.uses.add(((AsmDirectiveUses) asmDirective).getUses());
               } else if(asmDirective instanceof AsmDirectiveClobber) {
                  kickAsm.declaredClobber = ((AsmDirectiveClobber) asmDirective).getClobber();
               } else {
                  throw new CompileError("kickasm does not support directive " + asmDirective, StatementSource.kickAsm(ctx));
               }
            }
         }
         return kickAsm;
      }
      return null;
   }

   private interface AsmDirective {
   }

   @Override
   public List<AsmDirective> visitAsmDirectives(KickCParser.AsmDirectivesContext ctx) {
      ArrayList<AsmDirective> asmDirectives = new ArrayList<>();
      List<KickCParser.AsmDirectiveContext> params = ctx.asmDirective();
      for(KickCParser.AsmDirectiveContext param : params) {
         AsmDirective directive = (AsmDirective) visit(param);
         if(directive != null) {
            asmDirectives.add(directive);
         }
      }
      return asmDirectives;
   }

   /** KickAssembler directive specifying a constant used by the kickasm code. */
   public static class AsmDirectiveUses implements AsmDirective {
      /** constant/variable used by the KickAssembler-code. */
      private SymbolRef uses;

      public SymbolRef getUses() {
         return uses;
      }

      AsmDirectiveUses(SymbolRef uses) {
         this.uses = uses;
      }

      public void setUses(SymbolRef uses) {
         this.uses = uses;
      }

      @Override
      public String toString() {
         return "uses";
      }

   }

   @Override
   public Object visitAsmDirectiveName(KickCParser.AsmDirectiveNameContext ctx) {
      if("uses".equals(ctx.NAME(0).getText())) {
         String varName = ctx.NAME(1).getText();
         SymbolRef variableRef;
         Symbol symbol = getCurrentScope().findSymbol(varName);
         if(symbol != null) {
            //Found an existing variable
            variableRef = symbol.getRef();
         } else {
            // Either forward reference or a non-existing variable. Create a forward reference for later resolving.
            variableRef = new ForwardVariableRef(varName);
         }
         return new AsmDirectiveUses(variableRef);
      }
      throw new CompileError("Unknown ASM directive '"+ctx.NAME(0).getText()+"'", new StatementSource(ctx));
   }

   /** KickAssembler directive specifying the number of bytes for generated code/data. */
   public static class AsmDirectiveBytes implements AsmDirective {
      /** bytes for the KickAssembler-code. */
      private final RValue bytes;

      AsmDirectiveBytes(RValue bytes) {
         this.bytes = bytes;
      }

      public RValue getBytes() {
         return bytes;
      }

      @Override
      public String toString() {
         return "bytes";
      }
   }


   /** KickAssembler directive specifying the number of cycles for generated code/data. */
   public static class AsmDirectiveCycles implements AsmDirective {
      /** cycles for the KickAssembler-code. */
      private final RValue cycles;

      AsmDirectiveCycles(RValue cycles) {
         this.cycles = cycles;
      }

      public RValue getCycles() {
         return cycles;
      }

      @Override
      public String toString() {
         return "cycles";
      }

   }

   @Override
   public Object visitAsmDirectiveExpr(KickCParser.AsmDirectiveExprContext ctx) {
      if("cycles".equals(ctx.NAME().getText())) {
         if(ctx.expr() != null) {
            RValue cycles = (RValue) this.visit(ctx.expr());
            return new AsmDirectiveCycles(cycles);
         }
      } else if("bytes".equals(ctx.NAME().getText())) {
         if(ctx.expr() != null) {
            RValue bytes = (RValue) this.visit(ctx.expr());
            return new AsmDirectiveBytes(bytes);
         }
      }
      throw new CompileError("Unknown ASM directive '"+ctx.NAME().getText()+"'", new StatementSource(ctx));
   }

   /** ASM Directive specifying clobber registers. */
   private static class AsmDirectiveClobber implements AsmDirective {
      private final CpuClobber clobber;

      AsmDirectiveClobber(CpuClobber clobber) {
         this.clobber = clobber;
      }

      public CpuClobber getClobber() {
         return clobber;
      }

      @Override
      public String toString() {
         return "clobbers";
      }

   }

   @Override
   public Object visitAsmDirectiveString(KickCParser.AsmDirectiveStringContext ctx) {
      if("clobbers".equals(ctx.NAME().getText())) {
         String clobberString = ctx.STRING().getText().toUpperCase(Locale.ENGLISH);
         clobberString = clobberString.substring(1, clobberString.length() - 1);
         if(!clobberString.matches("[AXY]*")) {
            throw new CompileError("Illegal clobber value " + clobberString, new StatementSource(ctx));
         }
         CpuClobber clobber = new CpuClobber(clobberString);
         return new AsmDirectiveClobber(clobber);
      } else if("resource".equals(ctx.NAME().getText())) {
         TerminalNode resource = ctx.STRING();
         String resourceFileName = resource.getText();
         resourceFileName = resourceFileName.substring(1, resourceFileName.length() - 1);
         addResourceFile(ctx, resourceFileName);
         return null;
      }
      throw new CompileError("Unknown ASM directive '"+ctx.NAME().getText()+"'", new StatementSource(ctx));
   }

   /**
    * Add a resource to the program.
    * @param ctx The parser context used for finding the folder containing the current source line.
    * @param resourceFileName The file name of the resource file.
    */
   private void addResourceFile(ParserRuleContext ctx, String resourceFileName) {
      Path currentPath = cParser.getSourceFolderPath(ctx);
      File resourceFile = SourceLoader.loadFile(resourceFileName, currentPath, new ArrayList<>());
      if(resourceFile == null)
         throw new CompileError("File  not found " + resourceFileName);
      if(!program.getAsmResourceFiles().contains(resourceFile.toPath()))
         program.addAsmResourceFile(resourceFile.toPath());
      if(program.getLog().isVerboseParse()) {
         program.getLog().append("Added resource " + resourceFile.getPath().replace('\\', '/'));
      }
   }

   /** Information about a declared parameter. */
   static class ParameterDecl {
      final public String name;
      final public SymbolType type;

      public ParameterDecl(String name, SymbolType type) {
         this.name = name;
         this.type = type;
      }
   }


   /**
    * Holds type directives, comments etc. while parsing a variable or procedure declaration.
    * Has three levels of information pushed on top of each other:
    * <ol>
    *    <li>Struct Member Declaration (true while inside inside a struct declaration)</li>
    *    <li>Type information and directives (the type)</li>
    *    <li>Information about parameters (for procedures)</li>
    * </ol>
    * <p>
    * When parsing a declaration such as <code>volatile char a, * const b, c[]</code> the type level holds <code>volatile char</code>
    * and the variable level holds the pointer/array-information and the const-declaration for b.
    */
   static class VariableDeclaration {

      /** State specifying that we are currently populating struct members. */
      private boolean structMember = false;
      /** Holds directives that are not part of the type-spec (all other than const & volatile) when descending into a Variable Declaration. (type level) */
      private List<Directive> declDirectives = null;
      /** Holds the declared comments when descending into a Variable Declaration. (type level) */
      private List<Comment> declComments = null;
      /** The declared type (type level) */
      private SymbolType declType;
      /** The declared type (variable level) */
      private SymbolType varDeclType;
      /** The variable name (variable level) */
      private String varName;
      /** The declared parameters (if this is a procedure). */
      private List<ParameterDecl> parameters;

      /**
       * Exits the type layer (clears everything except struct information)
       */
      void exitType() {
         this.declDirectives = null;
         this.declComments = null;
         this.declType = null;
         this.varDeclType = null;
         this.varName = null;
         this.parameters = new ArrayList<>();
      }

      /**
       * Exits the variable layer (clears variable information)
       */
      void exitVar() {
         this.varDeclType = null;
         this.varName = null;
         this.parameters = new ArrayList<>();
      }

      SymbolType getEffectiveType() {
         if(this.varDeclType != null)
            return this.varDeclType;
         if(this.declType != null)
            return this.declType;
         return null;
      }

      /**
       * Set type-level directives. Splits directives into type-directives (const, volatile) and general directives (all other).
       *
       * @param directives The directives
       */
      void setDeclDirectives(List<Directive> directives) {
         this.declDirectives = new ArrayList<>();
         for(Directive directive : directives) {
            if(directive instanceof Directive.Volatile) {
               // Type-qualifier directive volatile
               this.declType = this.declType.getQualified(true, this.declType.isNomodify());
            } else if(directive instanceof Directive.Const) {
               // Type-qualifier directive const
               this.declType = this.declType.getQualified(this.declType.isVolatile(), true);
            } else {
               // variable directive
               if(!this.declDirectives.contains(directive))
                  this.declDirectives.add(directive);
            }
         }
      }

      private void setVarDeclTypeAndDirectives(SymbolType type, List<Directive> typeDirectives) {
         for(Directive directive : typeDirectives) {
            if(directive instanceof Directive.Const)
               type = type.getQualified(type.isVolatile(), true);
            if(directive instanceof Directive.Volatile)
               type = type.getQualified(true, type.isNomodify());
         }
         setVarDeclType(type);
      }

      public String getVarName() {
         return varName;
      }

      public void setVarName(String varName) {
         this.varName = varName;
      }

      List<Directive> getDeclDirectives() {
         return declDirectives;
      }

      List<Comment> getDeclComments() {
         return declComments;
      }

      boolean isStructMember() {
         return structMember;
      }

      public void setDeclType(SymbolType type) {
         this.declType = type;
      }

      void setVarDeclType(SymbolType varDeclType) {
         this.varDeclType = varDeclType;
      }

      void setDeclComments(List<Comment> declComments) {
         this.declComments = declComments;
      }

      void setStructMember(boolean structMember) {
         this.structMember = structMember;
      }

      public void setParameters(List<ParameterDecl> parameters) {
         this.parameters = parameters;
      }

      public List<ParameterDecl> getParameters() {
         return parameters;
      }

   }

   /** The current variable declaration. This is not on the stack. */
   private VariableDeclaration varDecl = new VariableDeclaration();

   /** The stack of variable type / directive declarataions being handled. Pushed/popped when entering into a nested type declaration (eg. struct member or a cast inside an initializer) */
   private final Deque<VariableDeclaration> varDeclStack = new LinkedList<>();

   /**
    * Push the current type declaration handler onto the stack and initialize a new empty current type declaration handler.
    */
   private void varDeclPush() {
      varDeclStack.push(varDecl);
      varDecl = new VariableDeclaration();
   }

   /**
    * Discard the current type declaration handler and pop the last one fron the stack.
    */
   private void varDeclPop() {
      this.varDecl = varDeclStack.pop();
   }

   /**
    * Visit the type/directive part of a declaration. Setup the local decl-variables
    *
    * @param ctx The declaration type & directives
    * @return null
    */
   @Override
   public Object visitDeclType(KickCParser.DeclTypeContext ctx) {
      varDecl.exitType();
      visit(ctx.type());
      varDecl.setDeclDirectives(getDirectives(ctx.directive()));
      varDecl.setDeclComments(getCommentsSymbol(ctx.getParent()));
      return null;
   }

   @Override
   public Object visitDeclVariables(KickCParser.DeclVariablesContext ctx) {
      this.visit(ctx.declType());
      this.visit(ctx.declaratorInitList());
      varDecl.exitType();
      return null;
   }

   @Override
   public Object visitDeclaratorInitList(KickCParser.DeclaratorInitListContext ctx) {
      if(ctx.declaratorInitList() != null)
         this.visit(ctx.declaratorInitList());
      this.visit(ctx.declaratorInit());
      varDecl.exitVar();
      return null;
   }

   private boolean isLutCall(KickCParser.ExprContext expr) {
      if(expr instanceof KickCParser.ExprCallContext) {
         KickCParser.ExprCallContext callCtx = (KickCParser.ExprCallContext) expr;
         if(callCtx.expr() instanceof KickCParser.ExprIdContext) {
            String name = callCtx.expr().getText();
            return "__builtin_lut".equals(name) || "lut".equals(name);
         }
      }
      return false;
   }

   private Object handleDeclVariableInitLut(KickCParser.DeclVariableInitExprContext ctx, KickCParser.ExprCallContext callCtx, StatementSource declSource) {
      String varName = varDecl.getVarName();
      SymbolType effectiveType = varDecl.getEffectiveType();
      if(!(effectiveType instanceof SymbolTypePointer) || ((SymbolTypePointer) effectiveType).getArraySpec() == null) {
         varDecl.exitVar();
         throw new CompileError("__builtin_lut only supported for array initializers, found " + effectiveType.toCDecl(), declSource);
      }
      SymbolTypePointer ptrType = (SymbolTypePointer) effectiveType;
      ArraySpec arraySpec = ptrType.getArraySpec();
      SymbolType elementType = ptrType.getElementType();

      KickCParser.ParameterListContext paramList = callCtx.parameterList();
      if(paramList == null || paramList.expr().size() != 2) {
         varDecl.exitVar();
         throw new CompileError("__builtin_lut takes exactly 2 arguments: (size, expression)", declSource);
      }

      RValue sizeRVal = (RValue) this.visit(paramList.expr(0));
      Long lutSize = evaluateConstantInteger(sizeRVal);
      if(lutSize == null || lutSize <= 0) {
         varDecl.exitVar();
         throw new CompileError("__builtin_lut size must be a positive compile-time constant integer", declSource);
      }

      if(arraySpec.getArraySize() != null) {
         Long declaredSize = evaluateConstantInteger(arraySpec.getArraySize());
         if(declaredSize != null && declaredSize.longValue() != lutSize.longValue()) {
            varDecl.exitVar();
            throw new CompileError("Array declared size " + declaredSize + " does not match __builtin_lut size " + lutSize, declSource);
         }
      } else {
         arraySpec.setArraySize(new ConstantInteger(lutSize));
      }

      KickCParser.ExprContext exprCtx = paramList.expr(1);
      String exprText;
      try {
         BufferedTokenStream preprocessedTokenStream = cParser.getPreprocessedTokenStream();
         exprText = preprocessedTokenStream.getText(exprCtx.start, exprCtx.stop);
      } catch(Exception e) {
         exprText = exprCtx.getText();
      }

      // Strip redundant C type casts like (uint8_t), (char), (int), etc.
      exprText = exprText.replaceAll("\\(\\s*(uint[0-9]+_t|int[0-9]+_t|unsigned\\s+char|signed\\s+char|char|unsigned\\s+int|signed\\s+int|int|unsigned\\s+short|signed\\s+short|short|unsigned\\s+long|signed\\s+long|long|word|byte|dword|sbyte)\\s*\\)", "");
      exprText = exprText.replaceAll("\\bM_PI\\b", "PI");

      String kickAsmCode;
      int elementSizeBytes = elementType.getSizeBytes();
      if(elementSizeBytes == 1) {
         kickAsmCode = ".fill " + lutSize + ", " + exprText;
      } else if(elementSizeBytes == 2) {
         kickAsmCode = ".fillword " + lutSize + ", " + exprText;
      } else if(elementSizeBytes == 4) {
         kickAsmCode = ".for(var i=0; i<" + lutSize + "; i++) .dword " + exprText;
      } else {
         varDecl.exitVar();
         throw new CompileError("__builtin_lut does not support element type " + elementType.toCDecl(), declSource);
      }

      ConstantArrayKickAsm constantArrayKickAsm = new ConstantArrayKickAsm(
         elementType,
         kickAsmCode,
         Collections.emptyList(),
         arraySpec.getArraySize()
      );

      final List<Directive> effectiveDirectives = varDecl.getDeclDirectives();
      final List<Comment> declComments = varDecl.getDeclComments();
      varDecl.exitVar();

      String segData = program.isLibraryFile(declSource != null ? declSource.getFileName() : null) ? Scope.SEGMENT_DATA_DEFAULT : currentSegmentData;
      VariableBuilder varBuilder = new VariableBuilder(varName, getCurrentScope(), false, false, effectiveType, effectiveDirectives, segData, program.getTargetPlatform().getVariableBuilderConfig());
      Variable variable = varBuilder.build();

      if(program.isSegmentRom(variable.getDataSegment())) {
         boolean isGlobalOrStatic = ScopeRef.ROOT.equals(variable.getScope().getRef()) || variable.isPermanent();
         if(!isGlobalOrStatic) {
            variable.setDataSegment(Scope.SEGMENT_DATA_DEFAULT);
         } else if(!variable.isNoModify() && !variable.isKindConstant() && !variable.isDeclarationOnly()) {
            throw new CompileError("Cannot allocate non-const variable '" + varName + "' in read-only ROM segment '" + variable.getDataSegment() + "'", declSource);
         }
      }

      variable.setInitValue(getConstInitValue(constantArrayKickAsm, null, declSource));
      variable.setComments(ensureUnusedComments(declComments));
      return null;
   }

   @Override
   public Object visitDeclVariableInitExpr(KickCParser.DeclVariableInitExprContext ctx) {
      this.visit(ctx.declarator());
      String varName = varDecl.getVarName();
      KickCParser.ExprContext initializer = ctx.expr();
      StatementSource declSource = new StatementSource((ParserRuleContext) ctx.parent.parent);
      if(ctx.bitfieldExpr() != null) {
         if(!varDecl.isStructMember()) {
            throw new CompileError("Bitfields are only allowed inside structs", declSource);
         }
         if(initializer != null) {
            throw new CompileError("Bitfields inside structs cannot have initializers", declSource);
         }
         RValue widthVal = (RValue) visit(ctx.bitfieldExpr().expr());
         if(!(widthVal instanceof ConstantInteger)) {
            throw new CompileError("Bitfield width must be a constant integer", declSource);
         }
         int width = ((ConstantInteger) widthVal).getInteger().intValue();
         if(width <= 0) {
            throw new CompileError("Named bitfield width must be positive: " + width, declSource);
         }
         final SymbolType effectiveType = varDecl.getEffectiveType();
         varDecl.exitVar();
         packBitfield(varName, width, effectiveType, declSource);
         return null;
      }
      if(currentBitfieldPackState != null) {
         currentBitfieldPackState.currentContainerName = null;
         currentBitfieldPackState.currentBitOffset = 0;
      }
      try {
         if(isLutCall(initializer)) {
            return handleDeclVariableInitLut(ctx, (KickCParser.ExprCallContext) initializer, declSource);
         }
         final SymbolType effectiveType = varDecl.getEffectiveType();
         if(effectiveType instanceof SymbolTypeProcedure) {
            // Declare the procedure
            boolean defineProcedure = varDecl.getDeclDirectives().stream().anyMatch(directive -> directive instanceof Directive.Intrinsic);
            declareProcedure(defineProcedure, ctx, declSource);
            varDecl.exitVar();
         } else {
            final boolean isStructMember = varDecl.isStructMember();
            final List<Directive> effectiveDirectives = varDecl.getDeclDirectives();
            final List<Comment> declComments = varDecl.getDeclComments();
            varDecl.exitVar();
            String segData = program.isLibraryFile(declSource != null ? declSource.getFileName() : null) ? Scope.SEGMENT_DATA_DEFAULT : currentSegmentData;
            VariableBuilder varBuilder = new VariableBuilder(varName, getCurrentScope(), false, false, effectiveType, effectiveDirectives, segData, program.getTargetPlatform().getVariableBuilderConfig());
            Variable variable = varBuilder.build();
            if(program.isSegmentRom(variable.getDataSegment())) {
               boolean isGlobalOrStatic = ScopeRef.ROOT.equals(variable.getScope().getRef()) || variable.isPermanent();
               if(!isGlobalOrStatic) {
                  variable.setDataSegment(Scope.SEGMENT_DATA_DEFAULT);
               } else if(!variable.isNoModify() && !variable.isKindConstant() && !variable.isDeclarationOnly()) {
                  throw new CompileError("Cannot allocate non-const variable '" + varName + "' in read-only ROM segment '" + variable.getDataSegment() + "'", declSource);
               }
            }
            if(isStructMember && (initializer != null))
               throw new CompileError("Initializer not supported inside structs " + effectiveType.toCDecl(), declSource);
            if(variable.isDeclarationOnly()) {
               if(initializer != null) {
                  throw new CompileError("Initializer not allowed for extern variables " + varName, declSource);
               }
            } else {
               // Create a proper initializer
               if(initializer != null)
                  PrePostModifierHandler.addPreModifiers(this, initializer, declSource);
               RValue initValue = (initializer == null) ? null : (RValue) visit(initializer);
               initValue = Initializers.constantify(initValue, new Initializers.ValueTypeSpec(effectiveType), program, declSource);
               boolean isPermanent = ScopeRef.ROOT.equals(variable.getScope().getRef()) || variable.isPermanent();
               if(variable.isKindConstant() || (isPermanent && variable.isKindLoadStore() && Variable.MemoryArea.MAIN_MEMORY.equals(variable.getMemoryArea()) && initValue instanceof ConstantValue && !isStructMember && variable.getRegister() == null)) {
                  // Set initial value
                  ConstantValue constInitValue = getConstInitValue(initValue, initializer, declSource);
                  variable.setInitValue(constInitValue);
                  // Add comments to constant
                  variable.setComments(ensureUnusedComments(declComments));
               } else if(!variable.isKindConstant() && !isStructMember) {

                  // The previous assignment of an intermediate variable that can be modified instead of creating a new statement
                  StatementLValue previousAssignment = null;

                  if(initValue instanceof VariableRef initVarRef) {
                     if(initVarRef.isIntermediate()) {
                        Statement previousStatement = getPreviousStatement();
                        if(previousStatement instanceof StatementLValue && ((StatementLValue) previousStatement).getlValue().equals(initVarRef)) {
                           previousAssignment = (StatementLValue) previousStatement;
                        }
                     }
                  }

                  Statement initStmt;
                  if(previousAssignment != null) {
                     previousAssignment.setlValue(variable.getVariableRef());
                     previousAssignment.setInitialAssignment(true);
                     previousAssignment.setSource(declSource);
                     initStmt = previousAssignment;
                  } else {
                     initStmt = new StatementAssignment(variable.getVariableRef(), initValue, true, declSource, Comment.NO_COMMENTS);
                     addStatement(initStmt);
                  }


                  if(variable.getScope().getRef().equals(ScopeRef.ROOT)) {
                     // Add comments to variable for global vars
                     variable.setComments(ensureUnusedComments(declComments));
                  } else {
                     // Add comments to statement for local vars
                     initStmt.setComments(ensureUnusedComments(declComments));
                  }

               }
               if(initializer != null)
                  PrePostModifierHandler.addPostModifiers(this, initializer, declSource);
            }
         }
      } catch(CompileError e) {
         throw new CompileError(e.getMessage(), declSource);
      }
      return null;
   }

   /**
    * Ensure that the initializer value is a constant. Fail if it is not
    *
    * @param initValue The initializer value (result from {@link Initializers#constantify(RValue, Initializers.ValueTypeSpec, Program, StatementSource)}
    * @param initializer The initializer
    * @param statementSource The source line
    * @return The constant initializer value
    */
   private ConstantValue getConstInitValue(RValue initValue, KickCParser.ExprContext initializer, StatementSource statementSource) {
      if(initializer != null && PrePostModifierHandler.hasPrePostModifiers(this, initializer, statementSource)) {
         throw new CompileError("Constant value contains a pre/post-modifier.", statementSource);
      }
      if(initValue instanceof ForwardVariableRef) {
         throw new CompileError("Variable used before being defined " + initValue, statementSource);
      }
      if(!(initValue instanceof ConstantValue))
         throw new CompileError("Initializer is not a constant value.", statementSource);
      return (ConstantValue) initValue;
   }

   @Override
   public Object visitDeclVariableInitKasm(KickCParser.DeclVariableInitKasmContext ctx) {
      this.visit(ctx.declarator());
      String varName = varDecl.getVarName();
      StatementSource statementSource = new StatementSource(ctx);
      SymbolType effectiveType = this.varDecl.getEffectiveType();
      if(!(effectiveType instanceof SymbolTypePointer) || ((SymbolTypePointer) effectiveType).getArraySpec() == null) {
         throw new CompileError("KickAsm initializers only supported for arrays " + varDecl.getEffectiveType().toCDecl(), statementSource);
      }
      // Add KickAsm statement
      KickAsm kasm = (KickAsm) this.visit(ctx.kasmContent());
      if(kasm.cycles != null) {
         throw new CompileError("KickAsm initializers does not support 'cycles' directive.", statementSource);
      }
      if(kasm.bytes != null) {
         throw new CompileError("KickAsm initializers does not support 'bytes' directive.", statementSource);
      }
      if(kasm.declaredClobber != null) {
         throw new CompileError("KickAsm initializers does not support 'clobbers' directive.", statementSource);
      }
      ConstantArrayKickAsm constantArrayKickAsm = new ConstantArrayKickAsm(((SymbolTypePointer) varDecl.getEffectiveType()).getElementType(), kasm.kickAsmCode, kasm.uses, ((SymbolTypePointer) effectiveType).getArraySpec().getArraySize());
      // Add a constant variable
      Scope scope = getCurrentScope();
      String segData = program.isLibraryFile(statementSource != null ? statementSource.getFileName() : null) ? Scope.SEGMENT_DATA_DEFAULT : currentSegmentData;
      VariableBuilder varBuilder = new VariableBuilder(varName, scope, false, false, varDecl.getEffectiveType(), varDecl.getDeclDirectives(), segData, program.getTargetPlatform().getVariableBuilderConfig());
      Variable variable = varBuilder.build();
      // Set constant value
      variable.setInitValue(getConstInitValue(constantArrayKickAsm, null, statementSource));
      // Add comments to constant
      variable.setComments(ensureUnusedComments(varDecl.getDeclComments()));
      varDecl.exitVar();
      return null;
   }

   /**
    * Find the directives in the parse tree
    *
    * @param directivesCtx The directives in the parse tree to examine
    * @return Objects representing the found directives
    */
   private List<Directive> getDirectives(List<KickCParser.DirectiveContext> directivesCtx) {
      List<Directive> directives = new ArrayList<>();
      for(KickCParser.DirectiveContext directiveContext : directivesCtx) {
         directives.add((Directive) this.visit(directiveContext));
      }
      return directives;
   }

   /**
    * Add declared directives to a procedure.
    *
    * @param procedure The procedure
    * @param directives The directives to add
    */
   private void addDirectives(Procedure procedure, List<Directive> directives) {
      for(Directive directive : directives) {
         if(directive instanceof Directive.Inline) {
            procedure.setDeclaredInline(true);
            procedure.setCallingConvention(Procedure.CallingConvention.PHI_CALL);
         } else if(directive instanceof Directive.Bank directiveBank) {
            Bank bank = new Bank(directiveBank.getBankArea(), directiveBank.getBankNumber());
            procedure.setBank(bank);
         } else if(directive instanceof Directive.CallingConvention) {
            procedure.setCallingConvention(((Directive.CallingConvention) directive).callingConvention);
         } else if(directive instanceof Directive.Interrupt) {
            procedure.setInterruptType(((Directive.Interrupt) directive).interruptType);
         } else if(directive instanceof Directive.ReserveZp) {
            procedure.setReservedZps(((Directive.ReserveZp) directive).reservedZp);
         } else if(directive instanceof Directive.Carry) {
            procedure.setCarryReturn(true);
         } else if(directive instanceof Directive.Intrinsic) {
            procedure.setDeclaredIntrinsic(true);
            //} else {
            //   throw new CompileError("Unsupported function directive " + directive.getName(), source);
         }
      }
   }

   /**
    * Add declared directives to a conditional jump (as part of a loop).
    *
    * @param conditional The loop conditional
    * @param directivesCtx The directives to add
    */
   private void addDirectives(StatementConditionalJump
                                    conditional, List<KickCParser.DirectiveContext> directivesCtx) {
      List<Directive> directives = getDirectives(directivesCtx);
      for(Directive directive : directives) {
         StatementSource source = new StatementSource(directivesCtx.get(0));
         if(directive instanceof Directive.Inline) {
            conditional.setDeclaredUnroll(true);
         } else if(directive instanceof Directive.Countdown) {
            conditional.setDeclaredCountdown(true);
         } else {
            throw new CompileError("Unsupported loop directive " + directive.getName(), source);
         }
      }
   }

   @Override
   public Directive visitDirectiveConst(KickCParser.DirectiveConstContext ctx) {
      return new Directive.Const();
   }

   @Override
   public Object visitDirectiveInline(KickCParser.DirectiveInlineContext ctx) {
      return new Directive.Inline();
   }

   @Override
   public Object visitDirectiveCarry(KickCParser.DirectiveCarryContext ctx) {
      return new Directive.Carry();
   }

   @Override
   public Object visitDirectiveLoHi(KickCParser.DirectiveLoHiContext ctx) {
      return new Directive.LoHi();
   }

   @Override
   public Object visitDirectiveCountdown(KickCParser.DirectiveCountdownContext ctx) {
      return new Directive.Countdown();
   }

   @Override
   public Object visitDirectiveBank(KickCParser.DirectiveBankContext ctx) {
      String bankArea = ctx.NAME().getText();
      Number bankNumber = NumberParser.parseLiteral(ctx.NUMBER().getText());
      return new Directive.Bank(bankArea, bankNumber.longValue());
   }

   @Override
   public Object visitDirectiveIntrinsic(KickCParser.DirectiveIntrinsicContext ctx) {
      return new Directive.Intrinsic();
   }

   @Override
   public Object visitDirectiveInterrupt(KickCParser.DirectiveInterruptContext ctx) {
      String interruptType;
      if(ctx.getChildCount() > 1) {
         interruptType = ctx.getChild(2).getText().toLowerCase(Locale.ENGLISH);
      } else {
         interruptType = currentInterruptType;
      }
      return new Directive.Interrupt(interruptType);
   }

   @Override
   public Directive visitDirectiveCallingConvention(KickCParser.DirectiveCallingConventionContext ctx) {
      Procedure.CallingConvention callingConvention = Procedure.CallingConvention.getCallingConvension(ctx.getText());
      return new Directive.CallingConvention(callingConvention);
   }

   @Override
   public Directive visitDirectiveAlign(KickCParser.DirectiveAlignContext ctx) {
      Number alignment = NumberParser.parseLiteral(ctx.NUMBER().getText());
      return new Directive.Align(alignment.intValue());
   }

   @Override
   public Directive visitDirectiveRegister(KickCParser.DirectiveRegisterContext ctx) {
      String name = null;
      if(ctx.NAME() != null) {
         name = ctx.NAME().getText();
      }
      if(name != null)
         return new Directive.NamedRegister(name);
      else
         return new Directive.Register();
   }

   @Override
   public Directive visitDirectiveMemoryAreaZp(KickCParser.DirectiveMemoryAreaZpContext ctx) {
      return new Directive.MemZp();
   }

   @Override
   public Directive visitDirectiveMemoryAreaMain(KickCParser.DirectiveMemoryAreaMainContext ctx) {
      return new Directive.MemMain();
   }

   @Override
   public Directive visitDirectiveMemoryAreaAddress(KickCParser.DirectiveMemoryAreaAddressContext ctx) {
      try {
         KickCParser.ExprContext initializer = ctx.expr();
         RValue initValue = (initializer == null) ? null : (RValue) visit(initializer);
         StatementSource statementSource = new StatementSource(ctx);
         ConstantValue addressAsConstantValue = getConstInitValue(initValue, initializer, statementSource);
         ConstantLiteral<?> literal = addressAsConstantValue.calculateLiteral(program.getScope());
         if(literal instanceof ConstantInteger) {
            Long address = ((ConstantInteger) literal).getValue();
            return new Directive.Address(addressAsConstantValue, address);
         } else {
            throw new CompileError("__address is not an integer :" + initValue.toString(program), new StatementSource(ctx));
         }

      } catch(NumberFormatException e) {
         throw new CompileError(e.getMessage(), new StatementSource(ctx));
      }
   }

   @Override
   public Directive visitDirectiveFormSsa(KickCParser.DirectiveFormSsaContext ctx) {
      return new Directive.FormSsa();
   }

   @Override
   public Directive visitDirectiveFormMa(KickCParser.DirectiveFormMaContext ctx) {
      return new Directive.FormMa();
   }

   @Override
   public Directive visitDirectiveVolatile(KickCParser.DirectiveVolatileContext ctx) {
      return new Directive.Volatile();
   }

   @Override
   public Object visitDirectiveStatic(KickCParser.DirectiveStaticContext ctx) {
      return new Directive.Static();
   }

   @Override
   public Object visitDirectiveExtern(KickCParser.DirectiveExternContext ctx) {
      return new Directive.Extern();
   }

   @Override
   public Directive visitDirectiveExport(KickCParser.DirectiveExportContext ctx) {
      return new Directive.Export();
   }

   @Override
   public Directive visitDirectiveReserveZp(KickCParser.DirectiveReserveZpContext ctx) {
      final List<KickCParser.PragmaParamContext> reserveParams = ctx.pragmaParam();
      final List<Integer> reservedZps = pragmaParamRanges(reserveParams);
      return new Directive.ReserveZp(reservedZps);
   }

   @Override
   public Void visitStmtSeq(KickCParser.StmtSeqContext ctx) {
      for(int i = 0; i < ctx.getChildCount(); i++) {
         KickCParser.StmtContext stmtContext = ctx.stmt(i);
         if(!(stmtContext instanceof KickCParser.StmtLabelContext)
            && !(stmtContext instanceof KickCParser.StmtWhileContext)
            && !(stmtContext instanceof KickCParser.StmtDoWhileContext)
            && !(stmtContext instanceof KickCParser.StmtForContext)
            && !(stmtContext instanceof KickCParser.StmtSwitchContext)) {
            pendingLoopLabel = null;
         }
         this.visit(stmtContext);
      }
      return null;
   }

   @Override
   public Void visitStmtBlock(KickCParser.StmtBlockContext ctx) {
      if(ctx.stmtSeq() != null) {
         BlockScope blockScope = getCurrentScope().addBlockScope();
         scopeStack.push(blockScope);
         this.visit(ctx.stmtSeq());
         scopeStack.pop();
      }
      return null;
   }

   @Override
   public Void visitStmtExpr(KickCParser.StmtExprContext ctx) {
      PrePostModifierHandler.addPreModifiers(this, ctx.commaExpr(), new StatementSource(ctx));
      beginNotConsumedTracking();
      RValue exprVal = (RValue) this.visit(ctx.commaExpr());
      if(notConsumed(exprVal)) {
         // Make a tmpVar to create the statement
         Variable tmpVar = addIntermediateVar();
         List<Comment> comments = ensureUnusedComments(getCommentsSymbol(ctx));
         RValue rVal = exprVal;
         if(exprVal instanceof LValue) {
            rVal = copyLValue((LValue) exprVal);
         }
         addStatement(new StatementAssignment((LValue) tmpVar.getRef(), rVal, true, new StatementSource(ctx), comments));
      }
      endNotConsumedTracking();
      PrePostModifierHandler.addPostModifiers(this, ctx.commaExpr(), new StatementSource(ctx));
      return null;
   }

   @Override
   public Void visitStmtIfElse(KickCParser.StmtIfElseContext ctx) {
      boolean hasInit = ctx.ifInit() != null;
      if(hasInit) {
         BlockScope blockScope = getCurrentScope().addBlockScope();
         scopeStack.push(blockScope);
         this.visit(ctx.ifInit());
      }
      KickCParser.StmtContext ifStmt = ctx.stmt(0);
      KickCParser.StmtContext elseStmt = ctx.stmt(1);
      RValue rValue = addCondition(ctx.commaExpr(), StatementSource.ifThen(ctx));
      List<Comment> comments = ensureUnusedComments(getCommentsSymbol(ctx));
      if(elseStmt == null) {
         // If without else - skip the entire section if condition not met
         SymbolVariableRef notExprVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) notExprVar, null, Operators.LOGIC_NOT, rValue, true, StatementSource.ifThen(ctx), comments));
         Label endJumpLabel = getCurrentScope().addLabelIntermediate();
         addStatement(new StatementConditionalJump(notExprVar, endJumpLabel.getRef(), StatementSource.ifThen(ctx), Comment.NO_COMMENTS));
         this.visit(ifStmt);
         // No else statement - just add the label
         addStatement(new StatementLabel(endJumpLabel.getRef(), StatementSource.ifThen(ctx), Comment.NO_COMMENTS));
      } else {
         // If with else - jump to if section if condition met - fall into else otherwise.
         Label ifJumpLabel = getCurrentScope().addLabelIntermediate();
         addStatement(new StatementConditionalJump(rValue, ifJumpLabel.getRef(), StatementSource.ifThen(ctx), comments));
         // Add else body
         this.visit(elseStmt);
         // There is an else statement - add the if part and any needed labels/jumps
         Label endJumpLabel = getCurrentScope().addLabelIntermediate();
         addStatement(new StatementJump(endJumpLabel.getRef(), StatementSource.ifElse(ctx), Comment.NO_COMMENTS));
         addStatement(new StatementLabel(ifJumpLabel.getRef(), StatementSource.ifThen(ctx), Comment.NO_COMMENTS));
         this.visit(ifStmt);
         StatementLabel endJumpTarget = new StatementLabel(endJumpLabel.getRef(), StatementSource.ifThen(ctx), Comment.NO_COMMENTS);
         addStatement(endJumpTarget);
      }
      if(hasInit) {
         scopeStack.pop();
      }
      pendingLoopLabel = null;
      return null;
   }

   /** A loop being generated. */
   static class Loop {

      /** The scope of the loop. */
      Scope loopScope;

      /** The label after the loop that a break will jump to. Null if no break has been encountered. */
      Label breakLabel;

      /** The label that a continue will jump to. Null if no continue has been encountered. */
      Label continueLabel;

      /** true if the loop is a switch-statement. */
      boolean isSwitch;

      String name;

      Loop(Scope loopScope, boolean isSwitch, String name) {
         this.loopScope = loopScope;
         this.isSwitch = isSwitch;
         this.name = name;
      }

      Label getBreakLabel() {
         return breakLabel;
      }

      Label getOrCreateBreakLabel() {
         if(breakLabel == null) {
            breakLabel = loopScope.addLabelIntermediate();
         }
         return breakLabel;
      }

      Label getContinueLabel() {
         return continueLabel;
      }

      void setContinueLabel(Label continueLabel) {
         this.continueLabel = continueLabel;
      }

      Label getOrCreateContinueLabel() {
         if(continueLabel == null) {
            continueLabel = loopScope.addLabelIntermediate();
         }
         return continueLabel;
      }

   }

   /** The loops being generated. */
   private final Stack<Loop> loopStack = new Stack<>();

   private String pendingLoopLabel;

   private String consumePendingLoopLabel() {
      String label = pendingLoopLabel;
      pendingLoopLabel = null;
      return label;
   }

   @Override
   public Void visitStmtWhile(KickCParser.StmtWhileContext ctx) {
      // Create the block scope early - to keep all statements of the loop inside it
      BlockScope blockScope = getCurrentScope().addBlockScope();
      scopeStack.push(blockScope);
      loopStack.push(new Loop(blockScope, false, consumePendingLoopLabel()));
      Label beginJumpLabel = getCurrentScope().addLabelIntermediate();
      Label doJumpLabel = getCurrentScope().addLabelIntermediate();
      Label endJumpLabel = getCurrentScope().addLabelIntermediate();
      List<Comment> comments = ensureUnusedComments(getCommentsSymbol(ctx));
      StatementLabel beginJumpTarget = new StatementLabel(beginJumpLabel.getRef(), StatementSource.whileDo(ctx), comments);
      addStatement(beginJumpTarget);
      RValue rValue = addCondition(ctx.commaExpr(), StatementSource.whileDo(ctx));
      StatementConditionalJump doJmpStmt = new StatementConditionalJump(rValue, doJumpLabel.getRef(), StatementSource.whileDo(ctx), Comment.NO_COMMENTS);
      addStatement(doJmpStmt);
      Statement endJmpStmt = new StatementJump(endJumpLabel.getRef(), StatementSource.whileDo(ctx), Comment.NO_COMMENTS);
      addStatement(endJmpStmt);
      StatementLabel doJumpTarget = new StatementLabel(doJumpLabel.getRef(), StatementSource.whileDo(ctx), Comment.NO_COMMENTS);
      addStatement(doJumpTarget);
      // Reuse the begin jump target for continue.
      loopStack.peek().setContinueLabel(beginJumpLabel);
      addLoopBody(ctx.stmt());
      Statement beginJmpStmt = new StatementJump(beginJumpLabel.getRef(), StatementSource.whileDo(ctx), Comment.NO_COMMENTS);
      addStatement(beginJmpStmt);
      StatementLabel endJumpTarget = new StatementLabel(endJumpLabel.getRef(), StatementSource.whileDo(ctx), Comment.NO_COMMENTS);
      addStatement(endJumpTarget);
      // Add directives
      addDirectives(doJmpStmt, ctx.directive());
      addLoopBreakLabel(loopStack.pop(), ctx);
      scopeStack.pop();
      return null;
   }

   /**
    * Add code to evaluate a comma-expr condition (used in while/for/...).
    *
    * @param conditionCtx The comma-expr condition to evaluate
    * @param statementSource The statement source used for errors
    * @return The RValue of the condition
    */
   private RValue addCondition(KickCParser.CommaExprContext conditionCtx, StatementSource statementSource) {
      // Add any pre-modifiers
      PrePostModifierHandler.addPreModifiers(this, conditionCtx, statementSource);
      RValue rValue = (RValue) this.visit(conditionCtx);
      // Add any post-modifiers
      if(PrePostModifierHandler.hasPostModifiers(this, conditionCtx, statementSource)) {
         // If modifiers are present the RValue must be assigned before the post-modifier is executed
         if(!(rValue instanceof VariableRef) || !((VariableRef) rValue).isIntermediate()) {
            // Make a new temporary variable and assign that
            Variable tmpVar = addIntermediateVar();
            Statement stmtExpr = new StatementAssignment(tmpVar.getVariableRef(), rValue, true, statementSource, Comment.NO_COMMENTS);
            addStatement(stmtExpr);
            rValue = tmpVar.getRef();
         }
         PrePostModifierHandler.addPostModifiers(this, conditionCtx, statementSource);
      }
      return rValue;
   }

   @Override
   public Void visitStmtDoWhile(KickCParser.StmtDoWhileContext ctx) {
      // Create the block scope early - to keep all statements of the loop inside it
      BlockScope blockScope = getCurrentScope().addBlockScope();
      scopeStack.push(blockScope);
      loopStack.push(new Loop(blockScope, false, consumePendingLoopLabel()));
      List<Comment> comments = ensureUnusedComments(getCommentsSymbol(ctx));
      Label beginJumpLabel = getCurrentScope().addLabelIntermediate();
      StatementLabel beginJumpTarget = new StatementLabel(beginJumpLabel.getRef(), StatementSource.doWhile(ctx), comments);
      addStatement(beginJumpTarget);
      addLoopBody(ctx.stmt());
      addLoopContinueLabel(loopStack.peek(), ctx);
      RValue rValue = addCondition(ctx.commaExpr(), StatementSource.doWhile(ctx));
      StatementConditionalJump doJmpStmt = new StatementConditionalJump(rValue, beginJumpLabel.getRef(), StatementSource.doWhile(ctx), Comment.NO_COMMENTS);
      addStatement(doJmpStmt);
      addDirectives(doJmpStmt, ctx.directive());
      addLoopBreakLabel(loopStack.pop(), ctx);
      scopeStack.pop();
      return null;
   }

   @Override
   public Object visitStmtSwitch(KickCParser.StmtSwitchContext ctx) {
      Loop containingLoop = null;
      if(!loopStack.isEmpty()) {
         containingLoop = loopStack.peek();
      }
      // Create a block scope - to keep all statements of the loop inside it
      BlockScope blockScope = getCurrentScope().addBlockScope();
      scopeStack.push(blockScope);
      if(ctx.ifInit() != null) {
         this.visit(ctx.ifInit());
      }
      Loop switchLoop = new Loop(blockScope, true, consumePendingLoopLabel());
      if(containingLoop != null) {
         switchLoop.setContinueLabel(containingLoop.getOrCreateContinueLabel());
      }
      loopStack.push(switchLoop);
      List<Comment> comments = ensureUnusedComments(getCommentsSymbol(ctx));
      // TODO: Add comments to next stmt
      // Evaluate the switch-expression
      RValue eValue = addCondition(ctx.commaExpr(), StatementSource.switchExpr(ctx));
      // Add case conditional jumps
      List<SwitchCaseBody> caseBodies = new ArrayList<>();
      for(KickCParser.SwitchCaseContext caseContext : ctx.switchCases().switchCase()) {
         List<Comment> caseComments = ensureUnusedComments(getCommentsSymbol(caseContext));
         RValue cValue = (RValue) this.visit(caseContext.expr());
         Label cJumpLabel = getCurrentScope().addLabelIntermediate();
         caseBodies.add(new SwitchCaseBody(cJumpLabel, caseContext.stmtSeq(), StatementSource.switchCase(caseContext)));
         StatementConditionalJump cJmpStmt = new StatementConditionalJump(eValue, Operators.EQ, cValue, cJumpLabel.getRef(), StatementSource.switchCase(caseContext), caseComments);
         addStatement(cJmpStmt);
      }
      // Add default ending jump
      Label dJumpLabel = getCurrentScope().addLabelIntermediate();
      StatementJump dJmpStmt = new StatementJump(dJumpLabel.getRef(), StatementSource.switchDefault(ctx.switchCases()), Comment.NO_COMMENTS);
      addStatement(dJmpStmt);
      // Add case labels & bodies
      for(SwitchCaseBody caseBody : caseBodies) {
         StatementLabel cJumpTarget = new StatementLabel(caseBody.cJumpLabel.getRef(), caseBody.statementSource, Comment.NO_COMMENTS);
         addStatement(cJumpTarget);
         if(caseBody.stmtSequence != null) {
            this.visit(caseBody.stmtSequence);
         }
      }
      // Add default label
      StatementLabel dJumpTarget = new StatementLabel(dJumpLabel.getRef(), StatementSource.switchDefault(ctx.switchCases()), Comment.NO_COMMENTS);
      addStatement(dJumpTarget);
      if(ctx.switchCases().stmtSeq() != null) {
         this.visit(ctx.switchCases().stmtSeq());
      }
      addLoopBreakLabel(loopStack.pop(), ctx);
      scopeStack.pop();
      return null;
   }

   /** Holds the body of a switch() case. */
   public static class SwitchCaseBody {
      /** Label for the statments of the case. */
      Label cJumpLabel;

      /** Statments of the case. */
      KickCParser.StmtSeqContext stmtSequence;

      /** Source of the case. */
      StatementSource statementSource;

      SwitchCaseBody(Label cJumpLabel, KickCParser.StmtSeqContext stmtSequence, StatementSource statementSource) {
         this.cJumpLabel = cJumpLabel;
         this.stmtSequence = stmtSequence;
         this.statementSource = statementSource;
      }
   }

   @Override
   public Object visitStmtFor(KickCParser.StmtForContext ctx) {
      this.visit(ctx.forLoop());
      return null;
   }

   @Override
   public Object visitForClassic(KickCParser.ForClassicContext ctx) {
      BlockScope blockScope = getCurrentScope().addBlockScope();
      scopeStack.push(blockScope);
      loopStack.push(new Loop(blockScope, false, consumePendingLoopLabel()));
      KickCParser.StmtForContext stmtForCtx = (KickCParser.StmtForContext) ctx.getParent();
      List<Directive> directives = getDirectives(stmtForCtx.directive());
      boolean isCountdown = directives.stream().anyMatch(d -> d instanceof Directive.Countdown);

      KickCParser.ForClassicConditionContext conditionCtx = ctx.forClassicCondition();
      KickCParser.CommaExprContext incrementCtx = ctx.commaExpr();

      // Check if forward loop can be inverted to countdown:
      boolean isForwardInvertable = false;
      String loopVarName = null;
      long countN = -1;
      if(isCountdown && conditionCtx != null && incrementCtx != null && incrementCtx.getText().contains("++")) {
         KickCParser.ExprContext condExpr = null;
         if(conditionCtx.commaExpr() instanceof KickCParser.CommaNoneContext cnone) {
            condExpr = cnone.expr();
         }
         if(condExpr instanceof KickCParser.ExprBinaryContext bin) {
            String op = bin.getChild(1).getText();
            if(("<".equals(op) || "<=".equals(op)) && bin.expr(0) instanceof KickCParser.ExprIdContext id) {
               loopVarName = id.NAME().getText();
               try {
                  if(bin.expr(1) instanceof KickCParser.ExprNumberContext num) {
                     countN = NumberParser.parseIntegerLiteral(num.getText()).getValue();
                  } else {
                     RValue rval = (RValue) this.visit(bin.expr(1));
                     if(rval instanceof ConstantInteger) {
                        countN = ((ConstantInteger) rval).getInteger();
                     }
                  }
                  if("<=".equals(op)) countN++;
                  if(countN > 0 && countN <= 128) {
                     isForwardInvertable = true;
                  }
               } catch(Exception ignored) {}
            }
         }
      }

      if(isForwardInvertable) {
         // Invert forward loop to 0-based countdown with bpl (N-1 down to 0)
         this.visit(ctx.forClassicInit());
         // Overwrite initial assignment from 0 to N-1
         Variable var = getCurrentScope().findVariable(loopVarName);
         if(var != null) {
            var.setType(SymbolType.SBYTE);
         }
         ProcedureCompilation procComp = getCurrentProcedureCompilation();
         if(var != null && procComp != null) {
            List<Statement> stmts = procComp.getStatementSequence().getStatements();
            for(int idx = stmts.size() - 1; idx >= 0; idx--) {
               Statement s = stmts.get(idx);
               if(s instanceof StatementAssignment assign && assign.getlValue() instanceof VariableRef vref && vref.getFullName().equals(var.getFullName())) {
                  assign.setrValue2(new ConstantInteger(countN - 1, var.getType()));
                  break;
               }
            }
         }

         Label doJumpLabel = getCurrentScope().addLabelIntermediate();
         Label endJumpLabel = getCurrentScope().addLabelIntermediate();
         List<Comment> comments = getCommentsSymbol(stmtForCtx);
         StatementLabel doJumpTarget = new StatementLabel(doJumpLabel.getRef(), StatementSource.forClassic(ctx), comments);
         addStatement(doJumpTarget);

         // Add body
         addLoopBody(stmtForCtx.stmt());

         // Add continue label
         addLoopContinueLabel(loopStack.peek(), ctx);

         // Decrement: var--
         Statement stmtDec = new StatementAssignment((LValue) var.getRef(), var.getRef(), Operators.MINUS, new ConstantInteger(1L, var.getType()), false, StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(stmtDec);

         // Condition at bottom: var >= 0 -> bpl
         Variable tmpVar = addIntermediateVar();
         Statement stmtCmp = new StatementAssignment((LValue) tmpVar.getRef(), var.getRef(), Operators.GE, new ConstantInteger(0L, var.getType()), true, StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(stmtCmp);
         StatementConditionalJump doJmpStmt = new StatementConditionalJump(tmpVar.getRef(), doJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(doJmpStmt);
         doJmpStmt.setDeclaredCountdown(true);

         StatementLabel endJumpTarget = new StatementLabel(endJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(endJumpTarget);
         addLoopBreakLabel(loopStack.pop(), ctx);
         scopeStack.pop();
         return null;
      }

      boolean isAlreadyCountdown = (incrementCtx != null && incrementCtx.getText().contains("--"));
      if(isCountdown || isAlreadyCountdown) {
         // Bottom-tested countdown loop
         this.visit(ctx.forClassicInit());
         Label doJumpLabel = getCurrentScope().addLabelIntermediate();
         Label endJumpLabel = getCurrentScope().addLabelIntermediate();
         List<Comment> comments = getCommentsSymbol(stmtForCtx);
         StatementLabel doJumpTarget = new StatementLabel(doJumpLabel.getRef(), StatementSource.forClassic(ctx), comments);
         addStatement(doJumpTarget);

         addLoopBody(stmtForCtx.stmt());
         addLoopContinueLabel(loopStack.peek(), ctx);

         if(incrementCtx != null) {
            PrePostModifierHandler.addPreModifiers(this, incrementCtx, StatementSource.forClassic(ctx));
            this.visit(incrementCtx);
            PrePostModifierHandler.addPostModifiers(this, incrementCtx, StatementSource.forClassic(ctx));
         }

         if(conditionCtx != null) {
            RValue conditionRvalue = addCondition(conditionCtx.commaExpr(), StatementSource.forClassic(ctx));
            StatementConditionalJump doJmpStmt = new StatementConditionalJump(conditionRvalue, doJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
            addStatement(doJmpStmt);
            doJmpStmt.setDeclaredCountdown(true);
            if(stmtForCtx.directive() != null)
               addDirectives(doJmpStmt, stmtForCtx.directive());
         } else {
            StatementJump doJmpStmt = new StatementJump(doJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
            addStatement(doJmpStmt);
         }

         StatementLabel endJumpTarget = new StatementLabel(endJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(endJumpTarget);
         addLoopBreakLabel(loopStack.pop(), ctx);
         scopeStack.pop();
         return null;
      }

      // Default classic while-shape loop
      this.visit(ctx.forClassicInit());
      Label beginJumpLabel = getCurrentScope().addLabelIntermediate();
      Label doJumpLabel = getCurrentScope().addLabelIntermediate();
      Label endJumpLabel = getCurrentScope().addLabelIntermediate();
      List<Comment> comments = getCommentsSymbol(stmtForCtx);
      StatementLabel repeatTarget = new StatementLabel(beginJumpLabel.getRef(), StatementSource.forClassic(ctx), comments);
      addStatement(repeatTarget);
      RValue conditionRvalue = null;
      if(conditionCtx != null) {
         conditionRvalue = addCondition(conditionCtx.commaExpr(), StatementSource.forClassic(ctx));
      }
      Statement doJmpStmt;
      if(conditionRvalue != null) {
         doJmpStmt = new StatementConditionalJump(conditionRvalue, doJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(doJmpStmt);
         Statement endJmpStmt = new StatementJump(endJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(endJmpStmt);
      } else {
         doJmpStmt = new StatementJump(doJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
         addStatement(doJmpStmt);
      }
      StatementLabel doJumpTarget = new StatementLabel(doJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
      addStatement(doJumpTarget);
      addLoopBody(stmtForCtx.stmt());
      addLoopContinueLabel(loopStack.peek(), ctx);
      if(incrementCtx != null) {
         PrePostModifierHandler.addPreModifiers(this, incrementCtx, StatementSource.forClassic(ctx));
         this.visit(incrementCtx);
         PrePostModifierHandler.addPostModifiers(this, incrementCtx, StatementSource.forClassic(ctx));
      }
      Statement beginJmpStmt = new StatementJump(beginJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
      addStatement(beginJmpStmt);
      StatementLabel endJumpTarget = new StatementLabel(endJumpLabel.getRef(), StatementSource.forClassic(ctx), Comment.NO_COMMENTS);
      addStatement(endJumpTarget);
      if(doJmpStmt instanceof StatementConditionalJump)
         addDirectives((StatementConditionalJump) doJmpStmt, stmtForCtx.directive());
      addLoopBreakLabel(loopStack.pop(), ctx);
      scopeStack.pop();
      return null;
   }


   @Override
   public Object visitIfInitDecl(KickCParser.IfInitDeclContext ctx) {
      if(ctx.declVariables() != null) {
         this.visit(ctx.declVariables());
      }
      return null;
   }

   @Override
   public Object visitIfInitExpr(KickCParser.IfInitExprContext ctx) {
      if(ctx.commaExpr() != null) {
         RValue rValue = (RValue) this.visit(ctx.commaExpr());
         consumeExpr(rValue);
      }
      return null;
   }

   @Override
   public Object visitForClassicInitDecl(KickCParser.ForClassicInitDeclContext ctx) {
      if(ctx.declVariables() != null) {
         this.visit(ctx.declVariables());
      }
      return null;
   }

   @Override
   public Object visitForRange(KickCParser.ForRangeContext ctx) {
      BlockScope blockScope = getCurrentScope().addBlockScope();
      scopeStack.push(blockScope);
      loopStack.push(new Loop(blockScope, false, consumePendingLoopLabel()));
      StatementSource statementSource = StatementSource.forRanged(ctx);
      this.visit(ctx.declType());
      this.visit(ctx.declarator());
      SymbolType varType = varDecl.getEffectiveType();
      String varName = varDecl.getVarName();
      Variable lValue;
      if(varType != null) {
         String segData = program.isLibraryFile(statementSource != null ? statementSource.getFileName() : null) ? Scope.SEGMENT_DATA_DEFAULT : currentSegmentData;
         if(program.isSegmentRom(segData)) {
            segData = Scope.SEGMENT_DATA_DEFAULT;
         }
         VariableBuilder varBuilder = new VariableBuilder(varName, blockScope, false, false, varType, varDecl.getDeclDirectives(), segData, program.getTargetPlatform().getVariableBuilderConfig());
         lValue = varBuilder.build();
      } else {
         lValue = getCurrentScope().findVariable(varName);
         if(lValue == null) {
            throw new CompileError("Loop variable not declared " + varName, statementSource);
         }
      }
      boolean initialAssignment = (varDecl.getEffectiveType() != null);
      varDecl.exitType();
      KickCParser.StmtForContext stmtForCtx = (KickCParser.StmtForContext) ctx.getParent();
      KickCParser.ExprContext rangeFirstCtx = ctx.expr(0);
      KickCParser.ExprContext rangeLastCtx = ctx.expr(1);
      // Assign loop variable with first value
      RValue rangeLastValue = (RValue) visit(rangeLastCtx);
      RValue rangeFirstValue = (RValue) visit(rangeFirstCtx);
      if(varType != null) {
         if(rangeFirstValue instanceof ConstantInteger) ((ConstantInteger) rangeFirstValue).setType(varType);
         if(rangeLastValue instanceof ConstantInteger) ((ConstantInteger) rangeLastValue).setType(varType);
      }
      Statement stmtInit = new StatementAssignment((LValue) lValue.getRef(), rangeFirstValue, initialAssignment, statementSource, Comment.NO_COMMENTS);
      addStatement(stmtInit);
      // Add label
      List<Comment> comments = ensureUnusedComments(getCommentsSymbol(stmtForCtx));
      Label repeatLabel = getCurrentScope().addLabelIntermediate();
      StatementLabel repeatTarget = new StatementLabel(repeatLabel.getRef(), statementSource, comments);
      addStatement(repeatTarget);
      // Add body
      addLoopBody(stmtForCtx.stmt());
      addLoopContinueLabel(loopStack.peek(), ctx);
      // Add increment
      Statement stmtNxt = new StatementAssignment((LValue) lValue.getRef(), lValue.getRef(), Operators.PLUS, new RangeNext(rangeFirstValue, rangeLastValue), false, statementSource, Comment.NO_COMMENTS);
      addStatement(stmtNxt);
      // Add condition i!=last+1 or i!=last-1
      RValue beyondLastVal = new RangeComparison(rangeFirstValue, rangeLastValue, lValue.getType());
      Variable tmpVar = addIntermediateVar();
      SymbolVariableRef tmpVarRef = tmpVar.getRef();
      Statement stmtTmpVar = new StatementAssignment((LValue) tmpVarRef, lValue.getRef(), Operators.NEQ, beyondLastVal, true, statementSource, Comment.NO_COMMENTS);
      addStatement(stmtTmpVar);
      // Add jump if condition was met
      StatementConditionalJump doJmpStmt = new StatementConditionalJump(tmpVarRef, repeatLabel.getRef(), statementSource, Comment.NO_COMMENTS);
      addStatement(doJmpStmt);
      addDirectives(doJmpStmt, stmtForCtx.directive());
      addLoopBreakLabel(loopStack.pop(), ctx);
      scopeStack.pop();
      return null;
   }

   private void addLoopBreakLabel(Loop loop, ParserRuleContext ctx) {
      if(loop.getBreakLabel() != null) {
         StatementLabel breakTarget = new StatementLabel(loop.getBreakLabel().getRef(), new StatementSource(ctx), Comment.NO_COMMENTS);
         addStatement(breakTarget);
      }
   }

   private void addLoopContinueLabel(Loop loop, ParserRuleContext ctx) {
      if(loop.getContinueLabel() != null) {
         StatementLabel continueTarget = new StatementLabel(loop.getContinueLabel().getRef(), new StatementSource(ctx), Comment.NO_COMMENTS);
         addStatement(continueTarget);
      }
   }

   private void addLoopBody(KickCParser.StmtContext stmt) {
      if(stmt != null) {
         if(stmt instanceof KickCParser.StmtBlockContext stmtBlockContext) {
            // Skip the block context and reuse the one created by the for() itself
            if(stmtBlockContext.stmtSeq() != null) {
               this.visit(stmtBlockContext.stmtSeq());
            }
         } else {
            this.visit(stmt);
         }
      }
   }

   @Override
   public Object visitStmtLabel(KickCParser.StmtLabelContext ctx) {
      String labelName = ctx.NAME().getText();
      Scope procedureScope = getCurrentProcedure();
      Scope targetScope = procedureScope != null ? procedureScope : getCurrentScope();
      if(targetScope.getLocalLabel(labelName) != null)
         throw new CompileError("label already defined '" + labelName + "'.", new StatementSource(ctx));
      Label label = targetScope.addLabel(labelName);
      addStatement(new StatementLabel(label.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      pendingLoopLabel = labelName;
      return null;
   }

   @Override
   public Object visitStmtGoto(KickCParser.StmtGotoContext ctx) {
      String labelName = ctx.NAME().getText();
      Scope procedureScope = getCurrentProcedure();
      Scope targetScope = procedureScope != null ? procedureScope : getCurrentScope();
      Label label = new Label(labelName, targetScope, false);
      addStatement(new StatementJump(label.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      return null;
   }

   @Override
   public Object visitStmtBreak(KickCParser.StmtBreakContext ctx) {
      if(loopStack.isEmpty()) {
         throw new CompileError("Break not inside a loop! ", new StatementSource(ctx));
      }
      Loop targetLoop = null;
      if(ctx.NAME() != null) {
         String name = ctx.NAME().getText();
         for(int i = loopStack.size() - 1; i >= 0; i--) {
            Loop loop = loopStack.get(i);
            if(name.equals(loop.name)) {
               targetLoop = loop;
               break;
            }
         }
         if(targetLoop == null) {
            throw new CompileError("Named break label '" + name + "' does not match any enclosing loop or switch! ", new StatementSource(ctx));
         }
      } else {
         targetLoop = loopStack.peek();
      }
      Label breakLabel = targetLoop.getOrCreateBreakLabel();
      Statement breakJmpStmt = new StatementJump(breakLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS);
      addStatement(breakJmpStmt);
      pendingLoopLabel = null;
      return null;
   }

   @Override
   public Object visitStmtContinue(KickCParser.StmtContinueContext ctx) {
      if(loopStack.isEmpty()) {
         throw new CompileError("Continue not inside a loop! ", new StatementSource(ctx));
      }
      Loop targetLoop = null;
      if(ctx.NAME() != null) {
         String name = ctx.NAME().getText();
         for(int i = loopStack.size() - 1; i >= 0; i--) {
            Loop loop = loopStack.get(i);
            if(name.equals(loop.name)) {
               if(loop.isSwitch) {
                  Label cont = loop.getContinueLabel();
                  if(cont == null) {
                     throw new CompileError("Cannot continue a switch statement! ", new StatementSource(ctx));
                  }
               }
               targetLoop = loop;
               break;
            }
         }
         if(targetLoop == null) {
            throw new CompileError("Named continue label '" + name + "' does not match any enclosing loop! ", new StatementSource(ctx));
         }
      } else {
         targetLoop = loopStack.peek();
      }
      Label continueLabel;
      if(targetLoop.isSwitch) {
         continueLabel = targetLoop.getContinueLabel();
         if(continueLabel == null) {
            throw new CompileError("Continue not inside a loop! ", new StatementSource(ctx));
         }
      } else {
         continueLabel = targetLoop.getOrCreateContinueLabel();
      }
      Statement continueJmpStmt = new StatementJump(continueLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS);
      addStatement(continueJmpStmt);
      pendingLoopLabel = null;
      return null;
   }

   @Override
   public Object visitStmtAsm(KickCParser.StmtAsmContext ctx) {
      // Find all defined labels in the ASM
      List<String> definedLabels = getAsmDefinedLabels(ctx);
      // Find all referenced symbols in the ASM
      Map<String, SymbolRef> referenced = getAsmReferencedSymbolVariables(ctx, definedLabels);
      List<Comment> comments = ensureUnusedComments(getCommentsSymbol(ctx));

      CpuClobber declaredClobber = null;
      if(ctx.asmDirectives() != null) {
         List<AsmDirective> asmDirectives = this.visitAsmDirectives(ctx.asmDirectives());
         for(AsmDirective asmDirective : asmDirectives) {
            if(asmDirective instanceof AsmDirectiveClobber) {
               declaredClobber = ((AsmDirectiveClobber) asmDirective).getClobber();
            } else {
               throw new CompileError("inline ASM does not support directive " + asmDirective, StatementSource.asm(ctx));
            }
         }
      }
      StatementAsm statementAsm = new StatementAsm(ctx.asmLines(), referenced, declaredClobber, StatementSource.asm(ctx), comments);
      addStatement(statementAsm);
      return null;
   }

   /**
    * Find all referenced symbol variables
    *
    * @param ctx An ASM statement
    * @param definedLabels All labels defined in the ASM
    * @return All variables/constants referenced in the ASM. Some may be ForwardVariableRefs to be resolved later.
    */
   private Map<String, SymbolRef> getAsmReferencedSymbolVariables(KickCParser.StmtAsmContext
                                                                        ctx, List<String> definedLabels) {
      Map<String, SymbolRef> referenced = new LinkedHashMap<>();
      KickCParserBaseVisitor<Void> findReferenced = new KickCParserBaseVisitor<>() {

         @Override
         public Void visitAsmExprBinary(KickCParser.AsmExprBinaryContext ctx) {
            ParseTree operator = ctx.getChild(1);
            if(operator.getText().equals(".")) {
               // Skip any . operator for now as it accesses data in another scope.
               // TODO Implement checking of labels/constants in other scopes
               return null;
            } else {
               return super.visitAsmExprBinary(ctx);
            }
         }

         @Override
         public Void visitAsmExprLabel(KickCParser.AsmExprLabelContext ctxLabel) {
            String label = ctxLabel.ASM_NAME().toString();
            if(label.equalsIgnoreCase("x") || label.equalsIgnoreCase("y") || label.equalsIgnoreCase("z") || label.equalsIgnoreCase("sp"))
               // Skip registers
               return super.visitAsmExprLabel(ctxLabel);
            if(!definedLabels.contains(label)) {
               // Look for the symbol
               Symbol symbol = getCurrentScope().findSymbol(ctxLabel.ASM_NAME().getText());
               if(symbol != null) {
                  referenced.put(label, symbol.getRef());
               } else {
                  // Either forward reference or a non-existing variable. Create a forward reference for later resolving.
                  referenced.put(label, new ForwardVariableRef(ctxLabel.ASM_NAME().getText()));
               }
            }
            return super.visitAsmExprLabel(ctxLabel);
         }
      };
      findReferenced.visit(ctx.asmLines());
      return referenced;
   }

   /**
    * Find all labels defined in the ASM (not multi-labels).
    *
    * @param ctx An ASM statement
    * @return All labels defined in the ASM.
    */
   private List<String> getAsmDefinedLabels(KickCParser.StmtAsmContext ctx) {
      List<String> definedLabels = new ArrayList<>();
      KickCParserBaseVisitor<Void> findDefinedLabels = new KickCParserBaseVisitor<>() {
         @Override
         public Void visitAsmLabelName(KickCParser.AsmLabelNameContext ctx) {
            String label = ctx.ASM_NAME().getText();
            definedLabels.add(label);
            return super.visitAsmLabelName(ctx);
         }
      };
      findDefinedLabels.visit(ctx.asmLines());
      return definedLabels;
   }

   @Override
   public Object visitStaticAssert(KickCParser.StaticAssertContext ctx) {
      RValue condition = (RValue) visit(ctx.expr());
      String message = null;
      if(ctx.STRING() != null) {
         String strText = ctx.STRING().getText();
         message = strText.substring(1, strText.lastIndexOf('"'));
      }
      program.addStaticAssert(new StaticAssert(condition, message, new StatementSource(ctx)));
      return null;
   }

   @Override
   public Object visitStmtStaticAssert(KickCParser.StmtStaticAssertContext ctx) {
      return visitStaticAssert(ctx.staticAssert());
   }

   @Override
   public Void visitStmtReturn(KickCParser.StmtReturnContext ctx) {
      Procedure procedure = getCurrentProcedure();
      KickCParser.CommaExprContext exprCtx = ctx.commaExpr();
      RValue rValue;
      if(exprCtx != null) {
         if(SymbolType.VOID.equals(procedure.getReturnType())) {
            throw new CompileError("Return value from void function " + procedure.getFullName(), new StatementSource(ctx));
         }
         PrePostModifierHandler.addPreModifiers(this, exprCtx, new StatementSource(ctx));
         rValue = (RValue) this.visit(exprCtx);
         Variable returnVar = procedure.getLocalVariable("return");
         addStatement(new StatementAssignment((LValue) returnVar.getRef(), rValue, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         PrePostModifierHandler.addPostModifiers(this, exprCtx, new StatementSource(ctx));
      }
      Label returnLabel = procedure.getLocalLabel(SymbolRef.PROCEXIT_BLOCK_NAME);
      addStatement(new StatementJump(returnLabel.getRef(), new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
      return null;
   }

   @Override
   public RValue visitInitUnion(KickCParser.InitUnionContext ctx) {
      final String memberName = ctx.NAME().getText();
      final RValue rValue = (RValue) visit(ctx.expr());
      return new UnionDesignator(memberName, rValue);
   }

   @Override
   public RValue visitInitList(KickCParser.InitListContext ctx) {
      List<RValue> initValues = new ArrayList<>();
      for(KickCParser.ExprContext initializer : ctx.expr()) {
         RValue rValue = (RValue) visit(initializer);
            initValues.add(rValue);
         }
      return new ValueList(initValues);
   }

   @Override
   public Object visitTypeSimple(KickCParser.TypeSimpleContext ctx) {
      StringBuilder typeName = new StringBuilder();
      for(TerminalNode simpleTypeNode : ctx.SIMPLETYPE()) {
         if(typeName.length() > 0)
            typeName.append(" ");
         typeName.append(simpleTypeNode.getText());
      }
      final SymbolType typeSimple = SymbolType.get(typeName.toString());
      if(typeSimple == null)
         throw new CompileError("Unknown type '" + typeName + "'", new StatementSource(ctx));
      varDecl.setDeclType(typeSimple);
      return null;
   }

   private static class BitfieldPackState {
      int containerIndex = 0;
      int currentBitOffset = 0;
      int containerCapacityBits = 8;
      String currentContainerName = null;
      SymbolType currentContainerType = null;
   }

   private BitfieldPackState currentBitfieldPackState = null;

   @Override
   public Object visitStructDef(KickCParser.StructDefContext ctx) {

      boolean isUnion = ctx.UNION() != null;

      String structDefName;
      if(ctx.NAME() != null) {
         structDefName = ctx.NAME().getText();
      } else {
         structDefName = program.getScope().allocateIntermediateVariableName();
      }
      StructDefinition structDefinition = program.getScope().addStructDefinition(structDefName, isUnion);
      scopeStack.push(structDefinition);
      BitfieldPackState prevBitfieldPackState = currentBitfieldPackState;
      currentBitfieldPackState = new BitfieldPackState();
      try {
         for(KickCParser.StructMembersContext memberCtx : ctx.structMembers()) {
            visit(memberCtx);
         }
      } finally {
         currentBitfieldPackState = prevBitfieldPackState;
      }
      scopeStack.pop();

      // C11 Anonymous Structure / Union support:
      // If unnamed and declared inside an enclosing struct or union, flatten its members into the parent struct!
      if(ctx.NAME() == null && !scopeStack.isEmpty() && scopeStack.peek() instanceof StructDefinition parentStruct) {
         long baseOffset = 0;
         if(!parentStruct.isUnion()) {
            baseOffset = ((SymbolTypeStruct) parentStruct.getType()).calculateSizeBytes(parentStruct, program.getScope());
         }
         for(Variable anonMember : structDefinition.getAllVars(false)) {
            String memberName = anonMember.getLocalName();
            if(parentStruct.getMember(memberName) != null) {
               throw new CompileError("Duplicate struct member '" + memberName + "' from anonymous " + (isUnion ? "union" : "struct"), new StatementSource(ctx));
            }
            long memberOffset = structDefinition.getMemberByteOffset(anonMember, program.getScope());
            long finalOffset = baseOffset + memberOffset;

            Variable flattenedVar = new VariableBuilder(memberName, parentStruct, false, false, anonMember.getType(), Collections.emptyList(), parentStruct.getSegmentData(), program.getTargetPlatform().getVariableBuilderConfig()).build();
            flattenedVar.setStructMemberOffset(finalOffset);
         }
      }

      varDecl.setDeclType(structDefinition.getType());
      return null;
   }

   @Override
   public Object visitDeclBitfieldUnnamed(KickCParser.DeclBitfieldUnnamedContext ctx) {
      StatementSource declSource = new StatementSource((ParserRuleContext) ctx.parent.parent);
      if(currentBitfieldPackState == null) {
         throw new CompileError("Unnamed bitfield is only allowed inside structs", declSource);
      }
      RValue widthVal = (RValue) visit(ctx.bitfieldExpr().expr());
      if(!(widthVal instanceof ConstantInteger)) {
         throw new CompileError("Bitfield width must be a constant integer", declSource);
      }
      int width = ((ConstantInteger) widthVal).getInteger().intValue();
      if(width == 0) {
         currentBitfieldPackState.currentContainerName = null;
         currentBitfieldPackState.currentBitOffset = 0;
      } else if(width > 0) {
         packBitfield(null, width, varDecl.getEffectiveType(), declSource);
      } else {
         throw new CompileError("Bitfield width cannot be negative: " + width, declSource);
      }
      return null;
   }

   private Variable packBitfield(String memberName, int width, SymbolType declaredType, StatementSource source) {
      if(width < 0 || width > 16) {
         throw new CompileError("Bitfield width " + width + " out of valid range (1..16)", source);
      }
      if(currentBitfieldPackState == null) {
         throw new CompileError("Bitfields are only allowed inside structs", source);
      }

      int unitCapacity = (declaredType.getSizeBytes() > 1 || width > 8) ? 16 : 8;
      SymbolType containerType = (unitCapacity == 16) ? SymbolType.WORD : SymbolType.BYTE;

      if(currentBitfieldPackState.currentContainerName == null
         || (currentBitfieldPackState.currentBitOffset + width > currentBitfieldPackState.containerCapacityBits)
         || (unitCapacity > currentBitfieldPackState.containerCapacityBits)) {

         String containerName = "__bf_" + currentBitfieldPackState.containerIndex++;
         StructDefinition structDef = (StructDefinition) getCurrentScope();
         new VariableBuilder(containerName, structDef, false, false, containerType, java.util.Collections.emptyList(), structDef.getSegmentData(), program.getTargetPlatform().getVariableBuilderConfig()).build();

         currentBitfieldPackState.currentContainerName = containerName;
         currentBitfieldPackState.currentBitOffset = 0;
         currentBitfieldPackState.containerCapacityBits = unitCapacity;
         currentBitfieldPackState.currentContainerType = containerType;
      }

      int bitOffset = currentBitfieldPackState.currentBitOffset;
      String containerName = currentBitfieldPackState.currentContainerName;
      currentBitfieldPackState.currentBitOffset += width;

      if(memberName == null) {
         return null;
      }

      StructDefinition structDef = (StructDefinition) getCurrentScope();
      Variable memberVar = new VariableBuilder(memberName, structDef, false, false, declaredType, java.util.Collections.emptyList(), structDef.getSegmentData(), program.getTargetPlatform().getVariableBuilderConfig()).build();
      memberVar.setBitField(true);
      memberVar.setBitFieldWidth(width);
      memberVar.setBitFieldOffset(bitOffset);
      memberVar.setBitFieldContainerName(containerName);

      return memberVar;
   }

   @Override
   public Object visitStructMemberVar(KickCParser.StructMemberVarContext ctx) {
      varDeclPush();
      varDecl.setStructMember(true);
      visit(ctx.declVariables());
      varDecl.setStructMember(false);
      varDeclPop();
      return null;
   }

   @Override
   public Object visitStructMemberAnon(KickCParser.StructMemberAnonContext ctx) {
      visit(ctx.structDef());
      return null;
   }

   @Override
   public Object visitStructMemberStaticAssert(KickCParser.StructMemberStaticAssertContext ctx) {
      visit(ctx.staticAssert());
      return null;
   }

   @Override
   public Object visitStructRef(KickCParser.StructRefContext ctx) {
      String structDefName = ctx.NAME().getText();
      StructDefinition structDefinition = program.getScope().getLocalStructDefinition(structDefName);
      if(structDefinition == null) {
         throw new CompileError("Unknown struct type " + structDefName, new StatementSource(ctx));
      }
      varDecl.setDeclType(structDefinition.getType());
      return null;
   }

   @Override
   public Object visitTypeEnumDef(KickCParser.TypeEnumDefContext ctx) {
      visit(ctx.enumDef());
      return null;
   }

   private EnumDefinition currentEnum = null;
   private SymbolType currentEnumUnderlyingType = null;

   @Override
   public Object visitEnumDef(KickCParser.EnumDefContext ctx) {
      try {
         String enumDefName;
         if(ctx.NAME() != null) {
            enumDefName = ctx.NAME().getText();
         } else {
            enumDefName = getCurrentScope().allocateIntermediateVariableName();
         }
         SymbolType enumUnderlyingType = SymbolType.BYTE;
         if(ctx.type() != null) {
            visit(ctx.type());
            enumUnderlyingType = varDecl.getEffectiveType();
         }
         EnumDefinition enumDefinition = new EnumDefinition(enumDefName, getCurrentScope());
         getCurrentScope().add(enumDefinition);
         this.currentEnum = enumDefinition;
         this.currentEnumUnderlyingType = enumUnderlyingType;
         scopeStack.push(currentEnum);
         visit(ctx.enumMemberList());
         scopeStack.pop();
         this.currentEnum = null;
         this.currentEnumUnderlyingType = null;
         // Copy all members to upper-level scope
         Scope parentScope = getCurrentScope();
         while(parentScope instanceof StructDefinition || parentScope instanceof TypeDefsScope)
            parentScope = parentScope.getScope();
         for(Variable member : enumDefinition.getAllConstants(false)) {
            parentScope.add(Variable.createConstant(member.getLocalName(), enumUnderlyingType, parentScope, member.getInitValue(), currentSegmentData));
         }
         varDecl.setDeclType(enumUnderlyingType);
         return null;
      } catch(CompileError e) {
         throw new CompileError(e.getMessage(), new StatementSource(ctx));
      }
   }

   @Override
   public Object visitEnumMember(KickCParser.EnumMemberContext ctx) {
      SymbolType underlyingType = currentEnumUnderlyingType != null ? currentEnumUnderlyingType : SymbolType.BYTE;
      String memberName = ctx.NAME().getText();
      ConstantValue enumValue;
      if(ctx.expr() != null) {
         RValue exprVal = (RValue) visit(ctx.expr());
         if(!(exprVal instanceof ConstantValue)) {
            throw new CompileError("Enum value not constant " + memberName, new StatementSource(ctx));
         }
         enumValue = (ConstantValue) exprVal;
      } else {
         // No specific value - find previous value
         List<Variable> values = new ArrayList<>(currentEnum.getAllConstants(false));
         if(values.isEmpty()) {
            enumValue = new ConstantInteger(0L, underlyingType);
         } else {
            Variable prevEnumMember = values.get(values.size() - 1);
            ConstantValue prevValue = prevEnumMember.getInitValue();
            if(prevValue instanceof ConstantInteger) {
               enumValue = new ConstantInteger(((ConstantInteger) prevValue).getInteger() + 1, underlyingType);
            } else {
               enumValue = new ConstantBinary(prevValue, Operators.PLUS, new ConstantInteger(1L, underlyingType));
            }
         }
      }
      currentEnum.add(Variable.createConstant(memberName, underlyingType, getCurrentScope(), enumValue, currentSegmentData));
      return null;
   }

   @Override
   public Object visitTypeEnumRef(KickCParser.TypeEnumRefContext ctx) {
      visit(ctx.enumRef());
      return null;
   }

   @Override
   public Object visitEnumRef(KickCParser.EnumRefContext ctx) {
      String enumDefName = ctx.NAME().getText();
      EnumDefinition enumDefinition = program.getScope().getLocalEnumDefinition(enumDefName);
      if(enumDefinition == null) {
         throw new CompileError("Unknown enum " + enumDefName, new StatementSource(ctx));
      }
      varDecl.setDeclType(SymbolType.BYTE);
      return null;
   }

   @Override
   public Object visitTypeName(KickCParser.TypeNameContext ctx) {
      varDeclPush();
      this.visit(ctx.type());
      this.visit(ctx.typeNameDeclarator());
      final SymbolType type = varDecl.getEffectiveType();
      varDeclPop();
      return type;
   }

   @Override
   public Object visitDeclaratorPointer(KickCParser.DeclaratorPointerContext ctx) {
      final SymbolType elementDeclType = varDecl.getEffectiveType();
      SymbolTypePointer pointerType = new SymbolTypePointer(elementDeclType);
      final List<Directive> typeDirectives = getDirectives(ctx.directive());
      varDecl.setVarDeclTypeAndDirectives(pointerType, typeDirectives);
      this.visit(ctx.declarator());
      return null;
   }

   @Override
   public Object visitTypeNameDeclaratorPointer(KickCParser.TypeNameDeclaratorPointerContext ctx) {
      final SymbolType elementDeclType = varDecl.getEffectiveType();
      SymbolTypePointer pointerType = new SymbolTypePointer(elementDeclType);
      final List<Directive> typeDirectives = getDirectives(ctx.directive());
      varDecl.setVarDeclTypeAndDirectives(pointerType, typeDirectives);
      this.visit(ctx.typeNameDeclarator());
      return varDecl.getEffectiveType();
   }

   @Override
   public Object visitDeclaratorArray(KickCParser.DeclaratorArrayContext ctx) {
      this.visit(ctx.declarator());
      // Handle array type declaration by updating the declared type and the array spec
      ArraySpec arraySpec;
      if(ctx.expr() != null) {
         varDeclPush();
         RValue sizeVal = (RValue) visit(ctx.expr());
         if(!(sizeVal instanceof ConstantValue))
            throw new CompileError(sizeVal.toString() + " is not constant or is not defined", new StatementSource(ctx));
         varDeclPop();
         arraySpec = new ArraySpec((ConstantValue) sizeVal);
      } else {
         arraySpec = new ArraySpec();
      }
      final SymbolType elementDeclType = varDecl.getEffectiveType();
      SymbolType arrayDeclType = new SymbolTypePointer(elementDeclType, arraySpec, false, false);
      varDecl.setVarDeclType(arrayDeclType);
      return null;
   }

   @Override
   public Object visitTypeNameDeclaratorArray(KickCParser.TypeNameDeclaratorArrayContext ctx) {
      this.visit(ctx.typeNameDeclarator());
      // Handle array type declaration by updating the declared type and the array spec
      ArraySpec arraySpec;
      if(ctx.expr() != null) {
         varDeclPush();
         RValue sizeVal = (RValue) visit(ctx.expr());
         if(!(sizeVal instanceof ConstantValue))
            throw new CompileError(sizeVal.toString() + " is not constant or is not defined", new StatementSource(ctx));
         varDeclPop();
         arraySpec = new ArraySpec((ConstantValue) sizeVal);
      } else {
         arraySpec = new ArraySpec();
      }
      final SymbolType elementDeclType = varDecl.getEffectiveType();
      SymbolType arrayDeclType = new SymbolTypePointer(elementDeclType, arraySpec, false, false);
      varDecl.setVarDeclType(arrayDeclType);
      return varDecl.getEffectiveType();
   }

   @Override
   public Object visitDeclaratorPar(KickCParser.DeclaratorParContext ctx) {
      this.visit(ctx.declarator());
      return null;
   }

   @Override
   public Object visitTypeNameDeclaratorPar(KickCParser.TypeNameDeclaratorParContext ctx) {
      this.visit(ctx.typeNameDeclarator());
      return varDecl.getEffectiveType();
   }

   @Override
   public Object visitDeclaratorName(KickCParser.DeclaratorNameContext ctx) {
      varDecl.setVarName(ctx.getText());
      return null;
   }

   @Override
   public Object visitTypeNameDeclaratorName(KickCParser.TypeNameDeclaratorNameContext ctx) {
      return null;
   }

   @Override
   public Object visitDeclaratorProcedure(KickCParser.DeclaratorProcedureContext ctx) {
      List<ParameterDecl> parameters = new ArrayList<>();
      List<SymbolType> paramTypes = new ArrayList<>();
      if(ctx.parameterListDecl() != null)
         for(KickCParser.ParameterDeclContext parameterDeclContext : ctx.parameterListDecl().parameterDecl()) {
            varDeclPush();
            ParameterDecl paramDecl = (ParameterDecl) this.visit(parameterDeclContext);
            // Handle parameter list with "VOID"
            if(SymbolType.VOID.equals(paramDecl.type) && ctx.parameterListDecl().parameterDecl().size() == 1)
               ; // Ignore the void parameter
            else {
               paramTypes.add(paramDecl.type);
               parameters.add(paramDecl);
            }
            varDeclPop();
         }
      SymbolType returnType = varDecl.getEffectiveType();
      varDecl.setVarDeclType(new SymbolTypeProcedure(returnType, paramTypes));
      varDecl.setParameters(parameters);
      visit(ctx.declarator());
      return null;
   }

   @Override
   public Object visitTypeNameDeclaratorProcedure(KickCParser.TypeNameDeclaratorProcedureContext ctx) {
      List<ParameterDecl> parameters = new ArrayList<>();
      List<SymbolType> paramTypes = new ArrayList<>();
      if(ctx.parameterListDecl() != null)
         for(KickCParser.ParameterDeclContext parameterDeclContext : ctx.parameterListDecl().parameterDecl()) {
            varDeclPush();
            ParameterDecl paramDecl = (ParameterDecl) this.visit(parameterDeclContext);
            // Handle parameter list with "VOID"
            if(SymbolType.VOID.equals(paramDecl.type) && ctx.parameterListDecl().parameterDecl().size() == 1)
               ; // Ignore the void parameter
            else {
               paramTypes.add(paramDecl.type);
               parameters.add(paramDecl);
            }
            varDeclPop();
         }
      SymbolType returnType = varDecl.getEffectiveType();
      varDecl.setVarDeclType(new SymbolTypeProcedure(returnType, paramTypes));
      varDecl.setParameters(parameters);
      visit(ctx.typeNameDeclarator());
      return varDecl.getEffectiveType();
   }

   @Override
   public Object visitTypeNamedRef(KickCParser.TypeNamedRefContext ctx) {
      Scope typeDefScope = program.getScope().getTypeDefScope();
      Variable typeDefVariable = typeDefScope.getLocalVar(ctx.getText());
      if(typeDefVariable != null) {
         varDecl.setDeclType(typeDefVariable.getType());
         return null;
      }
      throw new CompileError("Unknown type " + ctx.getText(), new StatementSource(ctx));
   }

   @Override
   public Object visitTypeDef(KickCParser.TypeDefContext ctx) {
      Scope typedefScope = program.getScope().getTypeDefScope();
      scopeStack.push(typedefScope);
      this.visit(ctx.declType());
      this.visit(ctx.declarator());
      String typedefName = varDecl.getVarName();
      VariableBuilder varBuilder = new VariableBuilder(typedefName, getCurrentScope(), false, false, varDecl.getEffectiveType(), varDecl.getDeclDirectives(), currentSegmentData, program.getTargetPlatform().getVariableBuilderConfig());
      varBuilder.build();
      scopeStack.pop();
      varDecl.exitType();
      return null;
   }

   /**
    * RValues that have not yet been output as part of a statement.
    * The expression visitor methods updates this so that the surrounding
    * statement can make sure to output any rValue that has not been output.
    * Null if we are not currently monitoring this.
    */
   private Set<RValue> exprNotConsumed = null;

   /**
    * Begins monitoring list of expressions not consumed.
    */
   private void beginNotConsumedTracking() {
      exprNotConsumed = new LinkedHashSet<>();
   }

   /**
    * Ends monitoring list of expressions not consumed.
    */
   private void endNotConsumedTracking() {
      exprNotConsumed = null;
   }

   /**
    * Consumes an RValue by outputting it as part of a statement.
    * This helps ensure that all expression RValues are output in statements
    *
    * @param rValue The RValue being consume
    */
   private void consumeExpr(RValue rValue) {
      if(exprNotConsumed != null)
         exprNotConsumed.remove(rValue);
   }

   /**
    * Marks an expression that has been produced which has not been output as part of a statement.
    * When the RValue is output in a statement it will be consumed using {@link #consumeExpr(RValue)}.
    *
    * @param rValue The RValue that has been produced but not consumed
    */
   private void addExprToConsume(RValue rValue) {
      if(exprNotConsumed != null)
         exprNotConsumed.add(rValue);
   }

   /**
    * Examines whether an RValue is in the list of non-consumed RValues.
    *
    * @return true if the RValue is in the list
    */
   private boolean notConsumed(RValue rValue) {
      return exprNotConsumed.contains(rValue);
   }

   public Variable getBitfieldMember(KickCParser.ExprContext exprCtx) {
      while(exprCtx instanceof KickCParser.ExprParContext) {
         KickCParser.CommaExprContext commaCtx = ((KickCParser.ExprParContext) exprCtx).commaExpr();
         if(commaCtx instanceof KickCParser.CommaNoneContext) {
            exprCtx = ((KickCParser.CommaNoneContext) commaCtx).expr();
         } else if(commaCtx instanceof KickCParser.CommaSimpleContext) {
            exprCtx = ((KickCParser.CommaSimpleContext) commaCtx).expr();
         } else {
            return null;
         }
      }
      if(exprCtx instanceof KickCParser.ExprDotContext dotCtx) {
         SymbolType structType = inferExprType(dotCtx.expr());
         if(structType instanceof SymbolTypeStruct) {
            StructDefinition structDef = ((SymbolTypeStruct) structType).getStructDefinition(program.getScope());
            if(structDef != null) {
               Variable member = structDef.getMember(dotCtx.NAME().getText());
               if(member != null && member.isBitField()) return member;
            }
         }
      } else if(exprCtx instanceof KickCParser.ExprArrowContext arrowCtx) {
         SymbolType ptrType = inferExprType(arrowCtx.expr());
         if(ptrType instanceof SymbolTypePointer && ((SymbolTypePointer) ptrType).getElementType() instanceof SymbolTypeStruct) {
            StructDefinition structDef = ((SymbolTypeStruct) ((SymbolTypePointer) ptrType).getElementType()).getStructDefinition(program.getScope());
            if(structDef != null) {
               Variable member = structDef.getMember(arrowCtx.NAME().getText());
               if(member != null && member.isBitField()) return member;
            }
         }
      }
      return null;
   }

   public LValue getBitfieldContainerLValue(KickCParser.ExprContext exprCtx, Variable bitfieldMember) {
      while(exprCtx instanceof KickCParser.ExprParContext) {
         KickCParser.CommaExprContext commaCtx = ((KickCParser.ExprParContext) exprCtx).commaExpr();
         if(commaCtx instanceof KickCParser.CommaNoneContext) {
            exprCtx = ((KickCParser.CommaNoneContext) commaCtx).expr();
         } else if(commaCtx instanceof KickCParser.CommaSimpleContext) {
            exprCtx = ((KickCParser.CommaSimpleContext) commaCtx).expr();
         }
      }
      if(exprCtx instanceof KickCParser.ExprDotContext dotCtx) {
         RValue structExpr = (RValue) visit(dotCtx.expr());
         return new StructMemberRef(structExpr, bitfieldMember.getBitFieldContainerName());
      } else if(exprCtx instanceof KickCParser.ExprArrowContext arrowCtx) {
         RValue structPtrExpr = (RValue) visit(arrowCtx.expr());
         return new StructMemberRef(new PointerDereferenceSimple(structPtrExpr), bitfieldMember.getBitFieldContainerName());
      }
      throw new CompileError("Unexpected bitfield expression: " + exprCtx.getText());
   }

   public RValue desugarBitfieldRead(ParserRuleContext ctx, StructMemberRef containerRef, Variable member) {
      int offset = member.getBitFieldOffset();
      int width = member.getBitFieldWidth();
      long valMask = (1L << width) - 1;
      int containerSize = member.getType().getSizeBytes();

      RValue val = containerRef;
      if(offset > 0) {
         val = addExprBinary(ctx, val, Operators.SHIFT_RIGHT, new ConstantInteger((long) offset));
      }
      boolean needsMask = !(offset == 0 && ((containerSize == 1 && width == 8) || (containerSize == 2 && width == 16)));
      if(needsMask) {
         val = addExprBinary(ctx, val, Operators.BOOL_AND, new ConstantInteger(valMask));
      }
      if((member.getType() instanceof SymbolTypeIntegerFixed && ((SymbolTypeIntegerFixed) member.getType()).isSigned()) && width < (containerSize * 8)) {
         long signBit = 1L << (width - 1);
         RValue xored = addExprBinary(ctx, val, Operators.BOOL_XOR, new ConstantInteger(signBit));
         val = addExprBinary(ctx, xored, Operators.MINUS, new ConstantInteger(signBit));
      }
      return val;
   }

   public RValue writeBitfield(ParserRuleContext ctx, LValue containerLVal, Variable bitfieldMember, RValue rhsVal) {
      int offset = bitfieldMember.getBitFieldOffset();
      int width = bitfieldMember.getBitFieldWidth();
      int containerSize = bitfieldMember.getType().getSizeBytes();
      long valMask = (1L << width) - 1;
      long fullMask = (valMask << offset);
      long invMask = (~fullMask) & (containerSize == 1 ? 0xFFL : 0xFFFFL);

      if(offset == 0 && ((containerSize == 1 && width == 8) || (containerSize == 2 && width == 16))) {
         Statement stmt = new StatementAssignment(containerLVal, rhsVal, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
         addStatement(stmt);
         consumeExpr(containerLVal);
         consumeExpr(rhsVal);
         return rhsVal;
      }

      RValue containerRVal = copyLValue(containerLVal);
      RValue cleared = addExprBinary(ctx, containerRVal, Operators.BOOL_AND, new ConstantInteger(invMask));

      RValue insertBits;
      if(rhsVal instanceof ConstantInteger) {
         long constVal = ((ConstantInteger) rhsVal).getInteger();
         long shiftedConst = (constVal & valMask) << offset;
         insertBits = new ConstantInteger(shiftedConst);
      } else {
         RValue maskedRhs = (width == (containerSize * 8)) ? rhsVal : addExprBinary(ctx, rhsVal, Operators.BOOL_AND, new ConstantInteger(valMask));
         if(offset > 0) {
            insertBits = addExprBinary(ctx, maskedRhs, Operators.SHIFT_LEFT, new ConstantInteger((long) offset));
         } else {
            insertBits = maskedRhs;
         }
      }

      RValue newContainerVal;
      if(insertBits instanceof ConstantInteger && ((ConstantInteger) insertBits).getInteger() == 0L) {
         newContainerVal = cleared;
      } else {
         newContainerVal = addExprBinary(ctx, cleared, Operators.BOOL_OR, insertBits);
      }

      Statement stmt = new StatementAssignment(containerLVal, newContainerVal, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(stmt);
      consumeExpr(containerLVal);
      consumeExpr(newContainerVal);

      return rhsVal;
   }

   public Long getLoHiArrayElementCount(Variable var) {
      if(var.getArraySpec() != null && var.getArraySpec().getArraySize() != null) {
         return evaluateConstantInteger(var.getArraySpec().getArraySize());
      }
      if(var.getInitValue() instanceof ConstantArrayList list) {
         return (long) list.getElements().size();
      }
      return null;
   }

   public Variable getLoHiArrayVariableFromArrayContext(KickCParser.ExprArrayContext arrayCtx) {
      KickCParser.ExprContext baseExpr = arrayCtx.expr();
      while(baseExpr instanceof KickCParser.ExprParContext) {
         KickCParser.CommaExprContext commaCtx = ((KickCParser.ExprParContext) baseExpr).commaExpr();
         if(commaCtx instanceof KickCParser.CommaNoneContext) {
            baseExpr = ((KickCParser.CommaNoneContext) commaCtx).expr();
         } else if(commaCtx instanceof KickCParser.CommaSimpleContext) {
            baseExpr = ((KickCParser.CommaSimpleContext) commaCtx).expr();
         } else {
            return null;
         }
      }
      if(baseExpr instanceof KickCParser.ExprIdContext idCtx) {
         String name = idCtx.NAME().getText();
         Symbol sym = getCurrentScope().findSymbol(name);
         if(sym == null) {
            sym = program.getScope().findSymbol(name);
         }
         if(sym instanceof Variable var && var.isLoHi()) {
            return var;
         }
      }
      return null;
   }

   public KickCParser.ExprArrayContext getLoHiArrayContext(KickCParser.ExprContext exprCtx) {
      while(exprCtx instanceof KickCParser.ExprParContext) {
         KickCParser.CommaExprContext commaCtx = ((KickCParser.ExprParContext) exprCtx).commaExpr();
         if(commaCtx instanceof KickCParser.CommaNoneContext) {
            exprCtx = ((KickCParser.CommaNoneContext) commaCtx).expr();
         } else if(commaCtx instanceof KickCParser.CommaSimpleContext) {
            exprCtx = ((KickCParser.CommaSimpleContext) commaCtx).expr();
         } else {
            return null;
         }
      }
      if(exprCtx instanceof KickCParser.ExprArrayContext arrayCtx) {
         Variable var = getLoHiArrayVariableFromArrayContext(arrayCtx);
         if(var != null) {
            return arrayCtx;
         }
      }
      return null;
   }

   public RValue desugarLoHiArrayRead(KickCParser.ExprArrayContext arrayCtx) {
      Variable var = getLoHiArrayVariableFromArrayContext(arrayCtx);
      Long count = getLoHiArrayElementCount(var);
      if(count == null) {
         throw new CompileError("Cannot determine size of __lohi array '" + var.getLocalName() + "'", new StatementSource(arrayCtx));
      }
      if(count > 256) {
         throw new CompileError("__lohi array cannot have more than 256 elements", new StatementSource(arrayCtx));
      }
      RValue indexRVal = (RValue) visit(arrayCtx.commaExpr());
      if(!(indexRVal instanceof SymbolVariableRef || indexRVal instanceof ConstantValue)) {
         SymbolVariableRef idxVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) idxVar, indexRVal, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         indexRVal = idxVar;
      }
      SymbolTypePointer bytePtrType = new SymbolTypePointer(SymbolType.BYTE);
      ConstantCastValue bytePtrLo = new ConstantCastValue(bytePtrType, (ConstantValue) var.getRef());
      ConstantBinary addN = new ConstantBinary(bytePtrLo, Operators.PLUS, new ConstantInteger(count, SymbolType.WORD));
      ConstantCastValue bytePtrHi = new ConstantCastValue(bytePtrType, addN);

      PointerDereferenceIndexed derefLo = new PointerDereferenceIndexed(bytePtrLo, indexRVal);
      SymbolVariableRef loVar = addIntermediateVar().getRef();
      addStatement(new StatementAssignment((LValue) loVar, derefLo, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));

      PointerDereferenceIndexed derefHi = new PointerDereferenceIndexed(bytePtrHi, indexRVal);
      SymbolVariableRef hiVar = addIntermediateVar().getRef();
      addStatement(new StatementAssignment((LValue) hiVar, derefHi, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));

      return addExprBinary(arrayCtx, hiVar, Operators.WORD, loVar);
   }

   public RValue writeLoHiArray(ParserRuleContext ctx, KickCParser.ExprArrayContext arrayCtx, RValue rhsVal) {
      Variable var = getLoHiArrayVariableFromArrayContext(arrayCtx);
      Long count = getLoHiArrayElementCount(var);
      if(count == null) {
         throw new CompileError("Cannot determine size of __lohi array '" + var.getLocalName() + "'", new StatementSource(arrayCtx));
      }
      if(count > 256) {
         throw new CompileError("__lohi array cannot have more than 256 elements", new StatementSource(arrayCtx));
      }
      RValue indexRVal = (RValue) visit(arrayCtx.commaExpr());
      if(!(indexRVal instanceof SymbolVariableRef || indexRVal instanceof ConstantValue)) {
         SymbolVariableRef idxVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) idxVar, indexRVal, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         indexRVal = idxVar;
      }
      if(!(rhsVal instanceof SymbolVariableRef || rhsVal instanceof ConstantValue)) {
         SymbolVariableRef rhsVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) rhsVar, rhsVal, true, new StatementSource(ctx), Comment.NO_COMMENTS));
         rhsVal = rhsVar;
      }
      SymbolTypePointer bytePtrType = new SymbolTypePointer(SymbolType.BYTE);
      ConstantCastValue bytePtrLo = new ConstantCastValue(bytePtrType, (ConstantValue) var.getRef());
      ConstantBinary addN = new ConstantBinary(bytePtrLo, Operators.PLUS, new ConstantInteger(count, SymbolType.WORD));
      ConstantCastValue bytePtrHi = new ConstantCastValue(bytePtrType, addN);

      RValue loByte = addExprUnary(ctx, Operators.BYTE0, rhsVal);
      PointerDereferenceIndexed derefLo = new PointerDereferenceIndexed(bytePtrLo, indexRVal);
      addStatement(new StatementAssignment(derefLo, loByte, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));

      RValue hiByte = addExprUnary(ctx, Operators.BYTE1, rhsVal);
      PointerDereferenceIndexed derefHi = new PointerDereferenceIndexed(bytePtrHi, indexRVal);
      addStatement(new StatementAssignment(derefHi, hiByte, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));

      return rhsVal;
   }

   public KickCParser.ExprArrayContext getNibbleArrayContext(KickCParser.ExprContext exprCtx) {
      while(exprCtx instanceof KickCParser.ExprParContext) {
         KickCParser.CommaExprContext commaCtx = ((KickCParser.ExprParContext) exprCtx).commaExpr();
         if(commaCtx instanceof KickCParser.CommaNoneContext) {
            exprCtx = ((KickCParser.CommaNoneContext) commaCtx).expr();
         } else if(commaCtx instanceof KickCParser.CommaSimpleContext) {
            exprCtx = ((KickCParser.CommaSimpleContext) commaCtx).expr();
         } else {
            return null;
         }
      }
      if(exprCtx instanceof KickCParser.ExprArrayContext arrayCtx) {
         SymbolType arrayType = inferExprType(arrayCtx.expr());
         if(arrayType instanceof SymbolTypePointer ptrType && SymbolType.NIBBLE.equals(ptrType.getElementType())) {
            return arrayCtx;
         }
      }
      return null;
   }

   public RValue desugarNibbleArrayRead(KickCParser.ExprArrayContext arrayCtx) {
      RValue arrayRVal = (RValue) visit(arrayCtx.expr());
      RValue indexRVal = (RValue) visit(arrayCtx.commaExpr());
      SymbolTypePointer bytePtrType = new SymbolTypePointer(SymbolType.BYTE);
      RValue bytePtr = (arrayRVal instanceof ConstantValue)
            ? new ConstantCastValue(bytePtrType, (ConstantValue) arrayRVal)
            : new CastValue(bytePtrType, arrayRVal);

      Long constIdx = evaluateConstantInteger(indexRVal);
      if(constIdx != null) {
         long idx = constIdx;
         long byteOffset = idx >> 1;
         boolean isOdd = (idx & 1) != 0;
         PointerDereferenceIndexed byteDeref = new PointerDereferenceIndexed(bytePtr, new ConstantInteger(byteOffset, SymbolType.WORD));
         SymbolVariableRef rawByteVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) rawByteVar, byteDeref, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         if(!isOdd) {
            return addExprBinary(arrayCtx, rawByteVar, Operators.BOOL_AND, new ConstantInteger(0x0fL, SymbolType.BYTE));
         } else {
            return addExprBinary(arrayCtx, rawByteVar, Operators.SHIFT_RIGHT, new ConstantInteger(4L, SymbolType.BYTE));
         }
      } else {
         SymbolVariableRef idxVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) idxVar, indexRVal, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));

         RValue byteIdx = addExprBinary(arrayCtx, idxVar, Operators.SHIFT_RIGHT, new ConstantInteger(1L));
         RValue isOdd = addExprBinary(arrayCtx, idxVar, Operators.BOOL_AND, new ConstantInteger(1L));

         PointerDereferenceIndexed byteDeref = new PointerDereferenceIndexed(bytePtr, byteIdx);
         SymbolVariableRef rawByteVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) rawByteVar, byteDeref, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));

         Label oddLabel = getCurrentScope().addLabelIntermediate();
         Label falseLabel = getCurrentScope().addLabelIntermediate();
         Label endJumpLabel = getCurrentScope().addLabelIntermediate();

         addStatement(new StatementConditionalJump(isOdd, oddLabel.getRef(), new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         addStatement(new StatementLabel(falseLabel.getRef(), new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         RValue evenVal = addExprBinary(arrayCtx, rawByteVar, Operators.BOOL_AND, new ConstantInteger(0x0fL, SymbolType.BYTE));
         SymbolVariableRef evenVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) evenVar, evenVal, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         LabelRef falseExitLabel = getCurrentProcedureCompilation().getStatementSequence().getCurrentBlockLabel();
         addStatement(new StatementJump(endJumpLabel.getRef(), new StatementSource(arrayCtx), Comment.NO_COMMENTS));

         addStatement(new StatementLabel(oddLabel.getRef(), new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         RValue oddVal = addExprBinary(arrayCtx, rawByteVar, Operators.SHIFT_RIGHT, new ConstantInteger(4L, SymbolType.BYTE));
         SymbolVariableRef oddVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) oddVar, oddVal, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         LabelRef trueExitLabel = getCurrentProcedureCompilation().getStatementSequence().getCurrentBlockLabel();

         addStatement(new StatementLabel(endJumpLabel.getRef(), new StatementSource(arrayCtx), Comment.NO_COMMENTS));
         StatementPhiBlock phiBlock = new StatementPhiBlock(Comment.NO_COMMENTS);
         phiBlock.setSource(new StatementSource(arrayCtx));
         SymbolVariableRef finalVar = addIntermediateVar().getRef();
         StatementPhiBlock.PhiVariable phiVariable = phiBlock.addPhiVariable((VariableRef) finalVar);
         phiVariable.setrValue(trueExitLabel, oddVar);
         phiVariable.setrValue(falseExitLabel, evenVar);
         addStatement(phiBlock);
         return finalVar;
      }
   }

   public RValue writeNibbleArray(ParserRuleContext ctx, KickCParser.ExprArrayContext arrayCtx, RValue rhsVal) {
      RValue arrayRVal = (RValue) visit(arrayCtx.expr());
      RValue indexRVal = (RValue) visit(arrayCtx.commaExpr());
      SymbolTypePointer bytePtrType = new SymbolTypePointer(SymbolType.BYTE);
      RValue bytePtr = (arrayRVal instanceof ConstantValue)
            ? new ConstantCastValue(bytePtrType, (ConstantValue) arrayRVal)
            : new CastValue(bytePtrType, arrayRVal);

      RValue maskedRhs = addExprBinary(ctx, rhsVal, Operators.BOOL_AND, new ConstantInteger(0x0fL, SymbolType.BYTE));

      Long constIdx = evaluateConstantInteger(indexRVal);
      if(constIdx != null) {
         long idx = constIdx;
         long byteOffset = idx >> 1;
         boolean isOdd = (idx & 1) != 0;
         PointerDereferenceIndexed readDeref = new PointerDereferenceIndexed(bytePtr, new ConstantInteger(byteOffset, SymbolType.WORD));
         SymbolVariableRef rawByteVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) rawByteVar, readDeref, true, new StatementSource(ctx), Comment.NO_COMMENTS));

         PointerDereferenceIndexed writeDeref = new PointerDereferenceIndexed(bytePtr, new ConstantInteger(byteOffset, SymbolType.WORD));
         if(!isOdd) {
            RValue maskedOld = addExprBinary(ctx, rawByteVar, Operators.BOOL_AND, new ConstantInteger(0xf0L, SymbolType.BYTE));
            RValue newByte = addExprBinary(ctx, maskedOld, Operators.BOOL_OR, maskedRhs);
            addStatement(new StatementAssignment(writeDeref, newByte, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         } else {
            RValue shiftedRhs = addExprBinary(ctx, maskedRhs, Operators.SHIFT_LEFT, new ConstantInteger(4L, SymbolType.BYTE));
            RValue maskedOld = addExprBinary(ctx, rawByteVar, Operators.BOOL_AND, new ConstantInteger(0x0fL, SymbolType.BYTE));
            RValue newByte = addExprBinary(ctx, maskedOld, Operators.BOOL_OR, shiftedRhs);
            addStatement(new StatementAssignment(writeDeref, newByte, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         }
         return maskedRhs;
      } else {
         SymbolVariableRef idxVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) idxVar, indexRVal, true, new StatementSource(arrayCtx), Comment.NO_COMMENTS));

         RValue byteIdx = addExprBinary(arrayCtx, idxVar, Operators.SHIFT_RIGHT, new ConstantInteger(1L));
         RValue isOdd = addExprBinary(arrayCtx, idxVar, Operators.BOOL_AND, new ConstantInteger(1L));

         PointerDereferenceIndexed readDeref = new PointerDereferenceIndexed(bytePtr, byteIdx);
         SymbolVariableRef rawByteVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) rawByteVar, readDeref, true, new StatementSource(ctx), Comment.NO_COMMENTS));

         Label oddLabel = getCurrentScope().addLabelIntermediate();
         Label falseLabel = getCurrentScope().addLabelIntermediate();
         Label endJumpLabel = getCurrentScope().addLabelIntermediate();

         addStatement(new StatementConditionalJump(isOdd, oddLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
         addStatement(new StatementLabel(falseLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
         RValue evenMaskedOld = addExprBinary(ctx, rawByteVar, Operators.BOOL_AND, new ConstantInteger(0xf0L, SymbolType.BYTE));
         RValue evenNewByte = addExprBinary(ctx, evenMaskedOld, Operators.BOOL_OR, maskedRhs);
         SymbolVariableRef evenVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) evenVar, evenNewByte, true, new StatementSource(ctx), Comment.NO_COMMENTS));
         LabelRef falseExitLabel = getCurrentProcedureCompilation().getStatementSequence().getCurrentBlockLabel();
         addStatement(new StatementJump(endJumpLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));

         addStatement(new StatementLabel(oddLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
         RValue shiftedRhs = addExprBinary(ctx, maskedRhs, Operators.SHIFT_LEFT, new ConstantInteger(4L, SymbolType.BYTE));
         RValue oddMaskedOld = addExprBinary(ctx, rawByteVar, Operators.BOOL_AND, new ConstantInteger(0x0fL, SymbolType.BYTE));
         RValue oddNewByte = addExprBinary(ctx, oddMaskedOld, Operators.BOOL_OR, shiftedRhs);
         SymbolVariableRef oddVar = addIntermediateVar().getRef();
         addStatement(new StatementAssignment((LValue) oddVar, oddNewByte, true, new StatementSource(ctx), Comment.NO_COMMENTS));
         LabelRef trueExitLabel = getCurrentProcedureCompilation().getStatementSequence().getCurrentBlockLabel();

         addStatement(new StatementLabel(endJumpLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
         StatementPhiBlock phiBlock = new StatementPhiBlock(Comment.NO_COMMENTS);
         phiBlock.setSource(new StatementSource(ctx));
         SymbolVariableRef finalNewByteVar = addIntermediateVar().getRef();
         StatementPhiBlock.PhiVariable phiVariable = phiBlock.addPhiVariable((VariableRef) finalNewByteVar);
         phiVariable.setrValue(trueExitLabel, oddVar);
         phiVariable.setrValue(falseExitLabel, evenVar);
         addStatement(phiBlock);

         PointerDereferenceIndexed writeDeref = new PointerDereferenceIndexed(bytePtr, byteIdx);
         addStatement(new StatementAssignment(writeDeref, finalNewByteVar, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         return maskedRhs;
      }
   }

   @Override
   public Object visitExprAssignment(KickCParser.ExprAssignmentContext ctx) {
      Variable bitfieldMember = getBitfieldMember(ctx.expr(0));
      if(bitfieldMember != null) {
         LValue containerLVal = getBitfieldContainerLValue(ctx.expr(0), bitfieldMember);
         RValue rhsVal = (RValue) visit(ctx.expr(1));
         return writeBitfield(ctx, containerLVal, bitfieldMember, rhsVal);
      }
      KickCParser.ExprArrayContext nibbleArrayCtx = getNibbleArrayContext(ctx.expr(0));
      if(nibbleArrayCtx != null) {
         RValue rhsVal = (RValue) visit(ctx.expr(1));
         return writeNibbleArray(ctx, nibbleArrayCtx, rhsVal);
      }
      KickCParser.ExprArrayContext loHiArrayCtx = getLoHiArrayContext(ctx.expr(0));
      if(loHiArrayCtx != null) {
         RValue rhsVal = (RValue) visit(ctx.expr(1));
         return writeLoHiArray(ctx, loHiArrayCtx, rhsVal);
      }
      Object val = visit(ctx.expr(0));
      if(val instanceof ConstantRef) {
         throw new CompileError("const variable may not be modified " + val, new StatementSource(ctx));
      }
      if(!(val instanceof LValue lValue)) {
         throw new CompileError("Illegal assignment Lvalue " + val.toString(), new StatementSource(ctx));
      }
      if(lValue instanceof VariableRef && ((VariableRef) lValue).isIntermediate()) {
         // Encountered an intermediate variable. This must be turned into a proper LValue later. Put it into a marker to signify that
         lValue = new LvalueIntermediate((VariableRef) lValue);
      }
      RValue rValue = (RValue) this.visit(ctx.expr(1));
      Statement stmt = new StatementAssignment(lValue, rValue, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(stmt);
      consumeExpr(lValue);
      consumeExpr(rValue);
      return lValue;
   }

   @Override
   public Object visitExprAssignmentCompound(KickCParser.ExprAssignmentCompoundContext ctx) {
      Variable bitfieldMember = getBitfieldMember(ctx.expr(0));
      if(bitfieldMember != null) {
         LValue containerLVal = getBitfieldContainerLValue(ctx.expr(0), bitfieldMember);
         RValue rhsVal = (RValue) visit(ctx.expr(1));
         String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
         Operator operator = Operators.getBinaryCompound(op);
         StructMemberRef containerRef = (StructMemberRef) copyLValue(containerLVal);
         RValue currentVal = desugarBitfieldRead(ctx, containerRef, bitfieldMember);
         RValue updatedVal = addExprBinary(ctx, currentVal, operator, rhsVal);
         return writeBitfield(ctx, containerLVal, bitfieldMember, updatedVal);
      }
      KickCParser.ExprArrayContext nibbleArrayCtx = getNibbleArrayContext(ctx.expr(0));
      if(nibbleArrayCtx != null) {
         RValue rhsVal = (RValue) visit(ctx.expr(1));
         String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
         Operator operator = Operators.getBinaryCompound(op);
         RValue currentVal = desugarNibbleArrayRead(nibbleArrayCtx);
         RValue updatedVal = addExprBinary(ctx, currentVal, operator, rhsVal);
         return writeNibbleArray(ctx, nibbleArrayCtx, updatedVal);
      }
      KickCParser.ExprArrayContext loHiArrayCtx = getLoHiArrayContext(ctx.expr(0));
      if(loHiArrayCtx != null) {
         RValue rhsVal = (RValue) visit(ctx.expr(1));
         String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
         Operator operator = Operators.getBinaryCompound(op);
         RValue currentVal = desugarLoHiArrayRead(loHiArrayCtx);
         RValue updatedVal = addExprBinary(ctx, currentVal, operator, rhsVal);
         return writeLoHiArray(ctx, loHiArrayCtx, updatedVal);
      }
      // Assignment (rValue/lValue)
      Object value = visit(ctx.expr(0));
      if(!(value instanceof LValue lValue)) {
         throw new CompileError("Illegal assignment Lvalue " + value.toString(), new StatementSource(ctx));
      }
      if(lValue instanceof VariableRef && ((VariableRef) lValue).isIntermediate()) {
         // Encountered an intermediate variable. This must be turned into a proper LValue later. Put it into a marker to signify that
         lValue = new LvalueIntermediate((VariableRef) lValue);
      }
      RValue rValue = (RValue) this.visit(ctx.expr(1));
      if(rValue instanceof LValue) {
         rValue = copyLValue((LValue) rValue);
      }
      // Binary Operator
      String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
      Operator operator = Operators.getBinaryCompound(op);
      // Assignment with operator
      LValue rValue1 = copyLValue(lValue);
      Statement stmt = new StatementAssignment(lValue, rValue1, operator, rValue, false, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(stmt);
      consumeExpr(lValue);
      consumeExpr(rValue);
      return lValue;
   }

   public static LValue copyLValue(LValue lValue) {
      if(lValue instanceof VariableRef) {
         return new VariableRef(((VariableRef) lValue).getFullName());
      } else if(lValue instanceof LvalueIntermediate) {
         return new LvalueIntermediate((VariableRef) copyLValue(((LvalueIntermediate) lValue).getVariable()));
      } else if(lValue instanceof PointerDereferenceSimple) {
         RValue pointer = ((PointerDereferenceSimple) lValue).getPointer();
         if(pointer instanceof LValue) {
            pointer = copyLValue((LValue) pointer);
         }
         return new PointerDereferenceSimple(pointer);
      } else if(lValue instanceof PointerDereferenceIndexed) {
         RValue pointer = ((PointerDereferenceIndexed) lValue).getPointer();
         if(pointer instanceof LValue) {
            pointer = copyLValue((LValue) pointer);
         }
         RValue index = ((PointerDereferenceIndexed) lValue).getIndex();
         if(index instanceof LValue) {
            index = copyLValue((LValue) index);
         }
         return new PointerDereferenceIndexed(pointer, index);
      } else if(lValue instanceof StructMemberRef) {
         return new StructMemberRef(copyLValue((LValue) ((StructMemberRef) lValue).getStruct()), ((StructMemberRef) lValue).getMemberName());
      } else if(lValue instanceof ForwardVariableRef) {
         return new ForwardVariableRef(((ForwardVariableRef) lValue).getName());
      } else {
         throw new CompileError("Unknown LValue type " + lValue);
      }
   }

   @Override
   public Object visitExprDot(KickCParser.ExprDotContext ctx) {
      SymbolType structType = inferExprType(ctx.expr());
      if(structType instanceof SymbolTypeStruct) {
         StructDefinition structDef = ((SymbolTypeStruct) structType).getStructDefinition(program.getScope());
         if(structDef != null) {
            Variable member = structDef.getMember(ctx.NAME().getText());
            if(member != null && member.isBitField()) {
               RValue structExpr = (RValue) visit(ctx.expr());
               StructMemberRef containerRef = new StructMemberRef(structExpr, member.getBitFieldContainerName());
               return desugarBitfieldRead(ctx, containerRef, member);
            }
         }
      }
      RValue structExpr = (RValue) visit(ctx.expr());
      String name = ctx.NAME().getText();
      StructMemberRef structMemberRef = new StructMemberRef(structExpr, name);
      addExprToConsume(structMemberRef);
      return structMemberRef;
   }

   @Override
   public Object visitExprArrow(KickCParser.ExprArrowContext ctx) {
      SymbolType ptrType = inferExprType(ctx.expr());
      if(ptrType instanceof SymbolTypePointer && ((SymbolTypePointer) ptrType).getElementType() instanceof SymbolTypeStruct) {
         StructDefinition structDef = ((SymbolTypeStruct) ((SymbolTypePointer) ptrType).getElementType()).getStructDefinition(program.getScope());
         if(structDef != null) {
            Variable member = structDef.getMember(ctx.NAME().getText());
            if(member != null && member.isBitField()) {
               RValue structPtrExpr = (RValue) visit(ctx.expr());
               StructMemberRef containerRef = new StructMemberRef(new PointerDereferenceSimple(structPtrExpr), member.getBitFieldContainerName());
               return desugarBitfieldRead(ctx, containerRef, member);
            }
         }
      }
      RValue structPtrExpr = (RValue) visit(ctx.expr());
      String name = ctx.NAME().getText();
      StructMemberRef structMemberRef = new StructMemberRef(new PointerDereferenceSimple(structPtrExpr), name);
      addExprToConsume(structMemberRef);
      return structMemberRef;
   }

   @Override
   public RValue visitExprCast(KickCParser.ExprCastContext ctx) {
      RValue child = (RValue) this.visit(ctx.expr());
      SymbolType castType = (SymbolType) this.visit(ctx.typeName());
      Operator operator = Operators.getCastUnary(castType);
      if(child instanceof ConstantValue) {
         consumeExpr(child);
         return new ConstantCastValue(castType, (ConstantValue) child);
      } else {
         return new CastValue(castType, child);
      }
   }

   @Override
   public Object visitExprSizeOf(KickCParser.ExprSizeOfContext ctx) {
      // sizeof(expression) - add a unary expression to be resolved later
      RValue child = (RValue) this.visit(ctx.expr());
      if(child instanceof ConstantValue) {
         return new ConstantUnary(Operators.SIZEOF, (ConstantValue) child);
      }
      Variable tmpVar = addIntermediateVar();
      SymbolVariableRef tmpVarRef = tmpVar.getRef();
      Statement stmt = new StatementAssignment((LValue) tmpVarRef, Operators.SIZEOF, child, true, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(stmt);
      consumeExpr(child);
      return tmpVarRef;
   }

   @Override
   public Object visitExprSizeOfType(KickCParser.ExprSizeOfTypeContext ctx) {
      // sizeof(type) - add directly
      SymbolType type = (SymbolType) this.visit(ctx.typeName());
      return SizeOfConstants.getSizeOfConstantVar(program.getScope(), type);
   }

   @Override
   public Object visitExprCountOf(KickCParser.ExprCountOfContext ctx) {
      RValue child = (RValue) this.visit(ctx.expr());
      if(child instanceof ConstantValue) {
         return new ConstantUnary(Operators.COUNTOF, (ConstantValue) child);
      }
      Variable tmpVar = addIntermediateVar();
      SymbolVariableRef tmpVarRef = tmpVar.getRef();
      Statement stmt = new StatementAssignment((LValue) tmpVarRef, Operators.COUNTOF, child, true, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(stmt);
      consumeExpr(child);
      return tmpVarRef;
   }

   @Override
   public Object visitExprCountOfType(KickCParser.ExprCountOfTypeContext ctx) {
      SymbolType type = (SymbolType) this.visit(ctx.typeName());
      if(type instanceof SymbolTypePointer arrayType && arrayType.getArraySpec() != null && arrayType.getArraySpec().getArraySize() != null) {
         return arrayType.getArraySpec().getArraySize();
      }
      throw new CompileError("countof() requires an array type with fixed size, found " + type.toCDecl(), new StatementSource(ctx));
   }

   @Override
   public Object visitExprTypeId(KickCParser.ExprTypeIdContext ctx) {
      // typeid(expression) - add a unary expression to be resolved later
      RValue child = (RValue) this.visit(ctx.expr());
      Variable tmpVar = addIntermediateVar();
      SymbolVariableRef tmpVarRef = tmpVar.getRef();
      Statement stmt = new StatementAssignment((LValue) tmpVarRef, Operators.TYPEID, child, true, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
      addStatement(stmt);
      consumeExpr(child);
      return tmpVarRef;
   }

   @Override
   public Object visitExprTypeIdType(KickCParser.ExprTypeIdTypeContext ctx) {
      // typeid(type) - add directly
      SymbolType type = (SymbolType) this.visit(ctx.typeName());
      return OperatorTypeId.getTypeIdConstantVar(program.getScope(), type);
   }

   public static boolean isCompatibleGeneric(SymbolType controlling, SymbolType assoc) {
      if(controlling == null || assoc == null) return false;
      SymbolType c = controlling.getQualified(false, false);
      SymbolType a = assoc.getQualified(false, false);
      if(c instanceof SymbolTypeProcedure) {
         c = new SymbolTypePointer(c);
      }
      if(c.equals(a)) return true;
      if(SymbolType.NUMBER.equals(c) && SymbolType.SWORD.equals(a)) return true;
      if(c instanceof SymbolTypePointer && a instanceof SymbolTypePointer) {
         SymbolTypePointer cp = (SymbolTypePointer) c;
         SymbolTypePointer ap = (SymbolTypePointer) a;
         if(cp.getArraySpec() != null && ap.getArraySpec() == null) {
            return cp.getElementType().getQualified(false, false).equals(ap.getElementType().getQualified(false, false));
         }
      }
      return false;
   }

   private SymbolType inferCommaExprType(KickCParser.CommaExprContext ctx) {
      if(ctx instanceof KickCParser.CommaNoneContext) {
         return inferExprType(((KickCParser.CommaNoneContext) ctx).expr());
      } else if(ctx instanceof KickCParser.CommaSimpleContext) {
         return inferExprType(((KickCParser.CommaSimpleContext) ctx).expr());
      }
      return null;
   }

   private SymbolType inferExprType(KickCParser.ExprContext ctx) {
      if(ctx == null) return null;
      if(ctx instanceof KickCParser.ExprParContext) {
         return inferCommaExprType(((KickCParser.ExprParContext) ctx).commaExpr());
      } else if(ctx instanceof KickCParser.ExprIdContext) {
         String name = ((KickCParser.ExprIdContext) ctx).NAME().getText();
         Symbol symbol = getCurrentScope().findSymbol(name);
         if(symbol != null) {
            return symbol.getType();
         }
         return null;
      } else if(ctx instanceof KickCParser.ExprNumberContext) {
         try {
            ConstantLiteral lit = NumberParser.parseIntegerLiteral(ctx.getText());
            return lit.getType(program.getScope());
         } catch(Exception e) {
            return SymbolType.NUMBER;
         }
      } else if(ctx instanceof KickCParser.ExprStringContext) {
         return new SymbolTypePointer(SymbolType.BYTE);
      } else if(ctx instanceof KickCParser.ExprCharContext) {
         return SymbolType.BYTE;
      } else if(ctx instanceof KickCParser.ExprBoolContext) {
         return SymbolType.BOOLEAN;
      } else if(ctx instanceof KickCParser.ExprNullPtrContext) {
         return new SymbolTypePointer(SymbolType.VOID);
      } else if(ctx instanceof KickCParser.ExprCastContext) {
         return (SymbolType) this.visit(((KickCParser.ExprCastContext) ctx).typeName());
      } else if(ctx instanceof KickCParser.ExprUnaryContext) {
         KickCParser.ExprUnaryContext unaryCtx = (KickCParser.ExprUnaryContext) ctx;
         String op = unaryCtx.getChild(0).getText();
         SymbolType operandType = inferExprType(unaryCtx.expr());
         if("&".equals(op)) {
            return operandType != null ? new SymbolTypePointer(operandType) : null;
         } else if("!".equals(op)) {
            return SymbolType.BOOLEAN;
         } else {
            OperatorUnary operatorUnary = Operators.getUnary(op);
            if(operatorUnary != null && operandType != null) {
               return operatorUnary.inferType(operandType);
            }
            return operandType;
         }
      } else if(ctx instanceof KickCParser.ExprPtrContext) {
         SymbolType ptrType = inferExprType(((KickCParser.ExprPtrContext) ctx).expr());
         if(ptrType instanceof SymbolTypePointer) {
            return ((SymbolTypePointer) ptrType).getElementType();
         }
         return null;
      } else if(ctx instanceof KickCParser.ExprPreModContext) {
         return inferExprType(((KickCParser.ExprPreModContext) ctx).expr());
      } else if(ctx instanceof KickCParser.ExprPostModContext) {
         return inferExprType(((KickCParser.ExprPostModContext) ctx).expr());
      } else if(ctx instanceof KickCParser.ExprBinaryContext) {
         KickCParser.ExprBinaryContext binCtx = (KickCParser.ExprBinaryContext) ctx;
         String op = binCtx.getChild(1).getText();
         if("==".equals(op) || "!=".equals(op) || "<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op) || "&&".equals(op) || "||".equals(op)) {
            return SymbolType.BOOLEAN;
         }
         SymbolType left = inferExprType(binCtx.expr(0));
         SymbolType right = inferExprType(binCtx.expr(1));
         OperatorBinary binOp = Operators.getBinary(op);
         if(binOp != null && left != null && right != null) {
            return binOp.inferType(left, right);
         }
         return left != null ? left : right;
      } else if(ctx instanceof KickCParser.ExprTernaryContext) {
         return inferExprType(((KickCParser.ExprTernaryContext) ctx).expr(1));
      } else if(ctx instanceof KickCParser.ExprDotContext) {
         KickCParser.ExprDotContext dotCtx = (KickCParser.ExprDotContext) ctx;
         SymbolType structType = inferExprType(dotCtx.expr());
         if(structType instanceof SymbolTypeStruct) {
            StructDefinition structDef = ((SymbolTypeStruct) structType).getStructDefinition(program.getScope());
            if(structDef != null) {
               Variable member = structDef.getMember(dotCtx.NAME().getText());
               if(member != null) return member.getType();
            }
         }
         return null;
      } else if(ctx instanceof KickCParser.ExprArrowContext) {
         KickCParser.ExprArrowContext arrowCtx = (KickCParser.ExprArrowContext) ctx;
         SymbolType ptrType = inferExprType(arrowCtx.expr());
         if(ptrType instanceof SymbolTypePointer && ((SymbolTypePointer) ptrType).getElementType() instanceof SymbolTypeStruct) {
            StructDefinition structDef = ((SymbolTypeStruct) ((SymbolTypePointer) ptrType).getElementType()).getStructDefinition(program.getScope());
            if(structDef != null) {
               Variable member = structDef.getMember(arrowCtx.NAME().getText());
               if(member != null) return member.getType();
            }
         }
         return null;
      } else if(ctx instanceof KickCParser.ExprArrayContext) {
         SymbolType arrType = inferExprType(((KickCParser.ExprArrayContext) ctx).expr());
         if(arrType instanceof SymbolTypePointer) {
            return ((SymbolTypePointer) arrType).getElementType();
         }
         return null;
      } else if(ctx instanceof KickCParser.ExprCallContext) {
         KickCParser.ExprCallContext callCtx = (KickCParser.ExprCallContext) ctx;
         SymbolType fnType = inferExprType(callCtx.expr());
         if(fnType instanceof SymbolTypeProcedure) {
            return ((SymbolTypeProcedure) fnType).getReturnType();
         }
         if(fnType instanceof SymbolTypePointer && ((SymbolTypePointer) fnType).getElementType() instanceof SymbolTypeProcedure) {
            return ((SymbolTypeProcedure) ((SymbolTypePointer) fnType).getElementType()).getReturnType();
         }
         return null;
      } else if(ctx instanceof KickCParser.ExprSizeOfContext || ctx instanceof KickCParser.ExprSizeOfTypeContext || ctx instanceof KickCParser.ExprCountOfContext || ctx instanceof KickCParser.ExprCountOfTypeContext || ctx instanceof KickCParser.ExprTypeIdContext || ctx instanceof KickCParser.ExprTypeIdTypeContext) {
         return SymbolType.WORD;
      } else if(ctx instanceof KickCParser.ExprAssignmentContext) {
         return inferExprType(((KickCParser.ExprAssignmentContext) ctx).expr(0));
      } else if(ctx instanceof KickCParser.ExprAssignmentCompoundContext) {
         return inferExprType(((KickCParser.ExprAssignmentCompoundContext) ctx).expr(0));
      } else if(ctx instanceof KickCParser.ExprGenericContext) {
         return inferGenericType((KickCParser.ExprGenericContext) ctx);
      }
      return null;
   }

   private SymbolType inferGenericType(KickCParser.ExprGenericContext ctx) {
      SymbolType controllingType = inferExprType(ctx.expr());
      if(controllingType == null) return null;
      KickCParser.ExprContext selectedExpr = null;
      KickCParser.ExprContext defaultExpr = null;
      for(KickCParser.GenericAssociationContext assocCtx : ctx.genericAssocList().genericAssociation()) {
         if(assocCtx instanceof KickCParser.GenericAssocDefaultContext) {
            defaultExpr = ((KickCParser.GenericAssocDefaultContext) assocCtx).expr();
         } else if(assocCtx instanceof KickCParser.GenericAssocTypeContext) {
            KickCParser.GenericAssocTypeContext typeAssoc = (KickCParser.GenericAssocTypeContext) assocCtx;
            SymbolType assocType = (SymbolType) this.visit(typeAssoc.typeName());
            if(selectedExpr == null && isCompatibleGeneric(controllingType, assocType)) {
               selectedExpr = typeAssoc.expr();
            }
         }
      }
      if(selectedExpr == null) selectedExpr = defaultExpr;
      return selectedExpr != null ? inferExprType(selectedExpr) : null;
   }

   @Override
   public Object visitExprGeneric(KickCParser.ExprGenericContext ctx) {
      SymbolType controllingType = inferExprType(ctx.expr());
      if(controllingType == null) {
         throw new CompileError("Cannot determine type of controlling expression in _Generic", new StatementSource(ctx));
      }

      KickCParser.ExprContext selectedExpr = null;
      KickCParser.ExprContext defaultExpr = null;
      List<SymbolType> seenTypes = new ArrayList<>();

      for(KickCParser.GenericAssociationContext assocCtx : ctx.genericAssocList().genericAssociation()) {
         if(assocCtx instanceof KickCParser.GenericAssocDefaultContext) {
            if(defaultExpr != null) {
               throw new CompileError("Multiple default cases in _Generic", new StatementSource(assocCtx));
            }
            defaultExpr = ((KickCParser.GenericAssocDefaultContext) assocCtx).expr();
         } else if(assocCtx instanceof KickCParser.GenericAssocTypeContext) {
            KickCParser.GenericAssocTypeContext typeAssoc = (KickCParser.GenericAssocTypeContext) assocCtx;
            SymbolType assocType = (SymbolType) this.visit(typeAssoc.typeName());
            for(SymbolType seen : seenTypes) {
               if(isCompatibleGeneric(seen, assocType)) {
                  throw new CompileError("Duplicate type in _Generic association list: " + assocType.toCDecl(), new StatementSource(typeAssoc));
               }
            }
            seenTypes.add(assocType);

            if(selectedExpr == null && isCompatibleGeneric(controllingType, assocType)) {
               selectedExpr = typeAssoc.expr();
            }
         }
      }

      if(selectedExpr == null) {
         if(defaultExpr != null) {
            selectedExpr = defaultExpr;
         } else {
            throw new CompileError("Controlling expression type " + controllingType.toCDecl() + " not matched in _Generic", new StatementSource(ctx));
         }
      }

      return this.visit(selectedExpr);
   }


   @Override
   public Object visitExprCall(KickCParser.ExprCallContext ctx) {
      List<RValue> parameters;
      KickCParser.ParameterListContext parameterList = ctx.parameterList();
      if(parameterList != null) {
         parameters = (List<RValue>) this.visit(parameterList);
      } else {
         parameters = new ArrayList<>();
      }

      for(RValue parameter : parameters) {
         consumeExpr(parameter);
      }

      if(ctx.expr() instanceof KickCParser.ExprIdContext) {
         String procedureName = ctx.expr().getText();
         // Handle the special BYTE0/1/2/3/WORD0/1/MAKEWORD/MAKEDWORD calls
         if(Pass1ByteXIntrinsicRewrite.INTRINSIC_BYTE0_NAME.equals(procedureName) && parameters.size() == 1) {
            return addExprUnary(ctx, Operators.BYTE0, parameters.get(0));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_BYTE1_NAME.equals(procedureName) && parameters.size() == 1) {
            return addExprUnary(ctx, Operators.BYTE1, parameters.get(0));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_BYTE2_NAME.equals(procedureName) && parameters.size() == 1) {
            return addExprUnary(ctx, Operators.BYTE2, parameters.get(0));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_BYTE3_NAME.equals(procedureName) && parameters.size() == 1) {
            return addExprUnary(ctx, Operators.BYTE3, parameters.get(0));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_WORD0_NAME.equals(procedureName) && parameters.size() == 1) {
            return addExprUnary(ctx, Operators.WORD0, parameters.get(0));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_WORD1_NAME.equals(procedureName) && parameters.size() == 1) {
            return addExprUnary(ctx, Operators.WORD1, parameters.get(0));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_MAKEWORD.equals(procedureName) && parameters.size() == 2) {
            return addExprBinary(ctx, parameters.get(0), Operators.WORD, parameters.get(1));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_MAKELONG.equals(procedureName) && parameters.size() == 2) {
            return addExprBinary(ctx, parameters.get(0), Operators.DWORD, parameters.get(1));
         } else if(Pass1ByteXIntrinsicRewrite.INTRINSIC_MAKELONG4.equals(procedureName)) {
            // Handle the intrinsic MAKELONG4()
            if(program.getScope().getGlobalSymbol(Pass1ByteXIntrinsicRewrite.INTRINSIC_MAKELONG4) == null) {
               // Add the intrinsic MAKEWORD4() to the global scope
               final Procedure makeword4 = new Procedure(
                     Pass1ByteXIntrinsicRewrite.INTRINSIC_MAKELONG4,
                     new SymbolTypeProcedure(SymbolType.DWORD, Arrays.asList(new SymbolType[]{SymbolType.BYTE, SymbolType.BYTE, SymbolType.BYTE, SymbolType.BYTE})),
                     program.getScope(),
                     Scope.SEGMENT_CODE_DEFAULT, Scope.SEGMENT_DATA_DEFAULT,
                     Procedure.CallingConvention.INTRINSIC_CALL, Bank.COMMON);
               makeword4.setDeclaredIntrinsic(true);
               final Variable hihi = new Variable("hihi", Variable.Kind.PHI_MASTER, SymbolType.BYTE, makeword4, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
               makeword4.add(hihi);
               final Variable hilo = new Variable("hilo", Variable.Kind.PHI_MASTER, SymbolType.BYTE, makeword4, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
               makeword4.add(hilo);
               final Variable lohi = new Variable("lohi", Variable.Kind.PHI_MASTER, SymbolType.BYTE, makeword4, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
               makeword4.add(lohi);
               final Variable lolo = new Variable("lolo", Variable.Kind.PHI_MASTER, SymbolType.BYTE, makeword4, Variable.MemoryArea.ZEROPAGE_MEMORY, Scope.SEGMENT_DATA_DEFAULT, null);
               makeword4.add(lolo);
               makeword4.setParameters(Arrays.asList(hihi, hilo, lohi, lolo));
               program.getScope().add(makeword4);
            }
            // Add the call
            LValue result = (LValue) addIntermediateVar().getRef();
            addStatement(new StatementCall(result, procedureName, parameters, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
            return result;
         } else if("__builtin_lut".equals(procedureName) || "lut".equals(procedureName)) {
            throw new CompileError("__builtin_lut can only be used to initialize array declarations", new StatementSource(ctx));
         } else if("delay_cycles".equals(procedureName) || "__builtin_delay_cycles".equals(procedureName)) {
            if(parameters.size() != 1) {
               throw new CompileError(procedureName + "() takes exactly 1 argument (number of cycles)", new StatementSource(ctx));
            }
            Long cycles = evaluateConstantInteger(parameters.get(0));
            if(cycles == null) {
               throw new CompileError(procedureName + "() requires a compile-time constant cycle count", new StatementSource(ctx));
            }
            if(cycles < 0) {
               throw new CompileError(procedureName + "() cycle count cannot be negative: " + cycles, new StatementSource(ctx));
            }
            ConstantCastValue voidVal = new ConstantCastValue(new SymbolTypePointer(SymbolType.VOID), new ConstantInteger(0L));
            consumeExpr(voidVal);
            if(cycles == 0) {
               return voidVal;
            }
            if(cycles == 1) {
               throw new CompileError(procedureName + "(1) is impossible on 6502: minimum instruction duration is 2 cycles", new StatementSource(ctx));
            }
            long remaining = cycles;
            StringBuilder kickAsmCode = new StringBuilder();
            long bytes = 0;
            CpuClobber clobber = CpuClobber.CLOBBER_NONE;
            if(remaining % 2 != 0) {
               kickAsmCode.append("bit $00\n");
               bytes += 2;
               remaining -= 3;
               clobber = new CpuClobber("nzv");
            }
            long numNops = remaining / 2;
            for(int i = 0; i < numNops; i++) {
               kickAsmCode.append("nop\n");
               bytes++;
            }
            StatementKickAsm statementKickAsm = new StatementKickAsm(
               kickAsmCode.toString(),
               new ConstantInteger(bytes, SymbolType.NUMBER),
               new ConstantInteger(cycles, SymbolType.NUMBER),
               java.util.Collections.emptyList(),
               clobber,
               new StatementSource(ctx),
               ensureUnusedComments(getCommentsSymbol(ctx))
            );
            addStatement(statementKickAsm);
            return voidVal;
         }
      }

      // Examine if the procedureName references a variable
      RValue procedurePointer = (RValue) this.visit(ctx.expr());
      if(procedurePointer instanceof ProcedureRef) {
         // A normal named call
         LValue result = (LValue) addIntermediateVar().getRef();
         String procedureName = ((ProcedureRef) procedurePointer).getLocalName();
         addStatement(new StatementCall(result, procedureName, parameters, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         return result;
      } else if(procedurePointer instanceof ForwardVariableRef) {
         // TODO: Remove the need for forward references!
         // Assume this is a named call to a yet undeclared function.
         LValue result = (LValue) addIntermediateVar().getRef();
         String procedureName = ((ForwardVariableRef) procedurePointer).getName();
         addStatement(new StatementCall(result, procedureName, parameters, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         return result;
      } else {
         LValue result = (LValue) addIntermediateVar().getRef();
         addStatement(new StatementCallPointer(result, procedurePointer, parameters, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx))));
         consumeExpr(procedurePointer);
         Label afterCallLabel = getCurrentScope().addLabelIntermediate();
         addStatement(new StatementLabel(afterCallLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
         return result;
      }
   }

   @Override
   public List<RValue> visitParameterList(KickCParser.ParameterListContext ctx) {
      List<RValue> parameters = new ArrayList<>();
      for(KickCParser.ExprContext exprContext : ctx.expr()) {
         RValue param = (RValue) this.visit(exprContext);
         parameters.add(param);
      }
      return parameters;
   }

   @Override
   public RValue visitExprArray(KickCParser.ExprArrayContext ctx) {
      if(getNibbleArrayContext(ctx) != null) {
         return desugarNibbleArrayRead(ctx);
      }
      if(getLoHiArrayVariableFromArrayContext(ctx) != null) {
         return desugarLoHiArrayRead(ctx);
      }
      RValue array = (RValue) visit(ctx.expr());
      RValue index = (RValue) visit(ctx.commaExpr());
      PointerDereferenceIndexed pointerDereferenceIndexed = new PointerDereferenceIndexed(array, index);
      addExprToConsume(pointerDereferenceIndexed);
      return pointerDereferenceIndexed;
   }

   @Override
   public RValue visitExprNullPtr(KickCParser.ExprNullPtrContext ctx) {
      return new ConstantCastValue(new SymbolTypePointer(SymbolType.VOID), new ConstantInteger(0L));
   }

   @Override
   public RValue visitExprNumber(KickCParser.ExprNumberContext ctx) {
      try {
         return NumberParser.parseIntegerLiteral(ctx.getText());
      } catch(NumberFormatException e) {
         throw new CompileError(e.getMessage(), new StatementSource(ctx));
      }
   }

   /** The current string encoding used if no explicit encoding is specified. */
   private StringEncoding currentEncoding;

   @Override
   public RValue visitExprString(KickCParser.ExprStringContext ctx) {
      StringBuilder stringValue = new StringBuilder();
      String subText;
      String lastSuffix = "";
      StringEncoding encoding = null;
      for(TerminalNode stringNode : ctx.STRING()) {
         subText = stringNode.getText();
         String suffix = subText.substring(subText.lastIndexOf('"') + 1);
         StringEncoding suffixEncoding = StringEncoding.fromSuffix(suffix, currentEncoding);
         if(suffixEncoding != null) {
            if(encoding != null && !encoding.equals(suffixEncoding)) {
               throw new CompileError("Cannot mix encodings in concatenated strings " + ctx.getText(), new StatementSource(ctx));
            }
            encoding = suffixEncoding;
         }
         lastSuffix = suffix;
         stringValue.append(subText, 1, subText.lastIndexOf('"'));
      }
      boolean zeroTerminated = !lastSuffix.contains("z");
      try {
         return new ConstantString(encoding.escapeToAscii(stringValue.toString()), encoding, zeroTerminated);
      } catch(CompileError e) {
         // Rethrow - adding statement context!
         throw new CompileError(e.getMessage(), new StatementSource(ctx));
      }
   }

   @Override
   public RValue visitExprBool(KickCParser.ExprBoolContext ctx) {
      String bool = ctx.getText();
      return new ConstantBool(Boolean.valueOf(bool));
   }

   @Override
   public Object visitExprChar(KickCParser.ExprCharContext ctx) {
      try {
         String fullText = ctx.getText();
         int lastQuote = fullText.lastIndexOf('\'');
         String charText = fullText.substring(1, lastQuote);
         String suffix = fullText.substring(lastQuote + 1);
         StringEncoding encoding = StringEncoding.fromSuffix(suffix, currentEncoding);
         char constChar = encoding.escapeToAscii(charText).charAt(0);
         return new ConstantChar(constChar, encoding);
      } catch(CompileError e) {
         // Rethrow adding source location
         throw new CompileError(e.getMessage(), new StatementSource(ctx));
      }
   }

   @Override
   public RValue visitExprBinary(KickCParser.ExprBinaryContext ctx) {
      RValue left = (RValue) this.visit(ctx.expr(0));
      RValue right = (RValue) this.visit(ctx.expr(1));
      String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
      Operator operator = Operators.getBinary(op);
      return addExprBinary(ctx, left, operator, right);
   }

   private RValue addExprBinary(ParserRuleContext ctx, RValue left, Operator operator, RValue right) {
      if(left instanceof ConstantValue && right instanceof ConstantValue) {
         return new ConstantBinary((ConstantValue) left, (OperatorBinary) operator, (ConstantValue) right);
      } else {
         Variable tmpVar = addIntermediateVar();
         SymbolVariableRef tmpVarRef = tmpVar.getRef();
         Statement stmt = new StatementAssignment((LValue) tmpVarRef, left, operator, right, true, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
         addStatement(stmt);
         consumeExpr(left);
         consumeExpr(right);
         return tmpVarRef;
      }
   }

   @Override
   public Object visitExprPtr(KickCParser.ExprPtrContext ctx) {
      RValue child = (RValue) this.visit(ctx.expr());
      PointerDereferenceSimple pointerDereferenceSimple = new PointerDereferenceSimple(child);
      addExprToConsume(pointerDereferenceSimple);
      return pointerDereferenceSimple;
   }

   @Override
   public RValue visitExprUnary(KickCParser.ExprUnaryContext ctx) {
      String op = ((TerminalNode) ctx.getChild(0)).getSymbol().getText();
      Operator operator = Operators.getUnary(op);
      if(Operators.ADDRESS_OF.equals(operator) && (getBitfieldMember(ctx.expr()) != null || getNibbleArrayContext(ctx.expr()) != null || getLoHiArrayContext(ctx.expr()) != null)) {
         throw new CompileError("Cannot take address of bitfield member or packed nibble element", new StatementSource(ctx));
      }
      RValue child = (RValue) this.visit(ctx.expr());
      return addExprUnary(ctx, operator, child);
   }

   private RValue addExprUnary(ParserRuleContext ctx, Operator operator, RValue child) {
      // Special handling of negative literal number
      if(operator.equals(Operators.ADDRESS_OF)) {
         ConstantValue constantAddressOf = constantifyAddressOf(child, new StatementSource(ctx));
         if(constantAddressOf != null)
            return constantAddressOf;
      }
      if(operator.equals(Operators.NEG) && child instanceof ConstantInteger) {
         return new ConstantInteger(-((ConstantInteger) child).getInteger(), ((ConstantInteger) child).getType());
      } else if(child instanceof ConstantValue) {
         return new ConstantUnary((OperatorUnary) operator, (ConstantValue) child);
      } else {
         Variable tmpVar = addIntermediateVar();
         SymbolVariableRef tmpVarRef = tmpVar.getRef();
         Statement stmt = new StatementAssignment((LValue) tmpVarRef, operator, child, true, new StatementSource(ctx), ensureUnusedComments(getCommentsSymbol(ctx)));
         addStatement(stmt);
         consumeExpr(child);
         return tmpVarRef;
      }
   }

   /**
    * Try to constantify an RValue that is affected by the address-of operator.
    *
    * @param child The sub expression of the address-of operator
    * @param source The statement source (used for errors)
    * @return The constant value of the pointer, if it can be constantified. Null otherwise.
    */
   private ConstantValue constantifyAddressOf(RValue child, StatementSource source) {
      if(child instanceof SymbolRef) {
         return new ConstantSymbolPointer((SymbolRef) child);
      } else if(child instanceof PointerDereferenceIndexed && ((PointerDereferenceIndexed) child).getPointer() instanceof ConstantValue && ((PointerDereferenceIndexed) child).getIndex() instanceof ConstantValue) {
         PointerDereferenceIndexed pointerDeref = (PointerDereferenceIndexed) child;
         return new ConstantBinary((ConstantValue) pointerDeref.getPointer(), Operators.PLUS, (ConstantValue) pointerDeref.getIndex());
      } else if(child instanceof StructMemberRef && ((StructMemberRef) child).getStruct() instanceof PointerDereferenceSimple && ((PointerDereferenceSimple) ((StructMemberRef) child).getStruct()).getPointer() instanceof ConstantValue) {
         final StructMemberRef structMemberRef = (StructMemberRef) child;
         final ConstantValue structPointer = (ConstantValue) ((PointerDereferenceSimple) structMemberRef.getStruct()).getPointer();
         final SymbolTypeStruct structType = (SymbolTypeStruct) SymbolTypeInference.inferType(program.getScope(), structMemberRef.getStruct());
         StructDefinition structDefinition = structType.getStructDefinition(program.getScope());
         final Variable member = structDefinition.getMember(structMemberRef.getMemberName());
         if(member == null) {
            throw new CompileError("Unknown struct member " + structMemberRef.getMemberName() + " in struct " + structType.toCDecl(), source);
         }
         final ConstantRef memberOffset = SizeOfConstants.getStructMemberOffsetConstant(program.getScope(), structDefinition, structMemberRef.getMemberName());
         return new ConstantCastValue(new SymbolTypePointer(member.getType()), new ConstantBinary(new ConstantCastValue(new SymbolTypePointer(SymbolType.BYTE), structPointer), Operators.PLUS, memberOffset));
      } else if(child instanceof StructMemberRef && ((StructMemberRef) child).getStruct() instanceof SymbolRef) {
         final StructMemberRef structMemberRef = (StructMemberRef) child;
         final ConstantValue structPointer = new ConstantSymbolPointer((SymbolRef) structMemberRef.getStruct());
         final SymbolTypeStruct structType = (SymbolTypeStruct) SymbolTypeInference.inferType(program.getScope(), structMemberRef.getStruct());
         StructDefinition structDefinition = structType.getStructDefinition(program.getScope());
         final Variable member = structDefinition.getMember(structMemberRef.getMemberName());
         if(member == null) {
            throw new CompileError("Unknown struct member " + structMemberRef.getMemberName() + " in struct " + structType.toCDecl(), source);
         }
         final ConstantRef memberOffset = SizeOfConstants.getStructMemberOffsetConstant(program.getScope(), structDefinition, structMemberRef.getMemberName());
         return new ConstantCastValue(new SymbolTypePointer(member.getType()), new ConstantBinary(new ConstantCastValue(new SymbolTypePointer(SymbolType.BYTE), structPointer), Operators.PLUS, memberOffset));
      }
      return null;
   }

   @Override
   public RValue visitExprTernary(KickCParser.ExprTernaryContext ctx) {
      RValue condValue = (RValue) this.visit(ctx.expr(0));
      Label trueLabel = getCurrentScope().addLabelIntermediate();
      Label falseLabel = getCurrentScope().addLabelIntermediate();
      Label endJumpLabel = getCurrentScope().addLabelIntermediate();
      addStatement(new StatementConditionalJump(condValue, trueLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      addStatement(new StatementLabel(falseLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      RValue falseValue = (RValue) this.visit(ctx.expr(2));
      SymbolVariableRef falseVar = addIntermediateVar().getRef();
      addStatement(new StatementAssignment((LValue) falseVar, falseValue, true, new StatementSource(ctx), Comment.NO_COMMENTS));
      LabelRef falseExitLabel = getCurrentProcedureCompilation().getStatementSequence().getCurrentBlockLabel();
      addStatement(new StatementJump(endJumpLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      addStatement(new StatementLabel(trueLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      RValue trueValue = (RValue) this.visit(ctx.expr(1));
      SymbolVariableRef trueVar = addIntermediateVar().getRef();
      addStatement(new StatementAssignment((LValue) trueVar, trueValue, true, new StatementSource(ctx), Comment.NO_COMMENTS));
      LabelRef trueExitLabel = getCurrentProcedureCompilation().getStatementSequence().getCurrentBlockLabel();
      addStatement(new StatementLabel(endJumpLabel.getRef(), new StatementSource(ctx), Comment.NO_COMMENTS));
      StatementPhiBlock phiBlock = new StatementPhiBlock(Comment.NO_COMMENTS);
      phiBlock.setSource(new StatementSource(ctx));
      SymbolVariableRef finalVar = addIntermediateVar().getRef();
      StatementPhiBlock.PhiVariable phiVariable = phiBlock.addPhiVariable((VariableRef) finalVar);
      phiVariable.setrValue(trueExitLabel, trueVar);
      phiVariable.setrValue(falseExitLabel, falseVar);
      addStatement(phiBlock);
      consumeExpr(condValue);
      consumeExpr(falseValue);
      consumeExpr(trueValue);
      return finalVar;
   }

   @Override
   public Object visitCommaNone(KickCParser.CommaNoneContext ctx) {
      return this.visit(ctx.expr());
   }

   @Override
   public Object visitCommaSimple(KickCParser.CommaSimpleContext ctx) {
      this.visit(ctx.commaExpr());
      return this.visit(ctx.expr());
   }

   @Override
   public Object visitExprPreMod(KickCParser.ExprPreModContext ctx) {
      return this.visit(ctx.expr());
   }

   @Override
   public Object visitExprPostMod(KickCParser.ExprPostModContext ctx) {
      return this.visit(ctx.expr());
   }

   @Override
   public RValue visitExprPar(KickCParser.ExprParContext ctx) {
      return (RValue) this.visit(ctx.commaExpr());
   }

   @Override
   public RValue visitExprId(KickCParser.ExprIdContext ctx) {
      Symbol symbol = getCurrentScope().findSymbol(ctx.NAME().getText());
      if(symbol instanceof Variable) {
         Variable variable = (Variable) symbol;
         if(variable.isLoHi()) {
            ParserRuleContext parent = ctx.getParent();
            while(parent instanceof KickCParser.ExprParContext || parent instanceof KickCParser.CommaExprContext) {
               parent = parent.getParent();
            }
            if(!(parent instanceof KickCParser.ExprSizeOfContext || parent instanceof KickCParser.ExprCountOfContext)) {
               throw new CompileError("Split __lohi array '" + variable.getLocalName() + "' cannot be used as a raw pointer", new StatementSource(ctx));
            }
         }
         return variable.getRef();
      } else if(symbol instanceof Procedure) {
         Procedure procedure = (Procedure) symbol;
         return procedure.getRef();
      } else if(symbol == null) {
         // Either forward reference or a non-existing variable. Create a forward reference for later resolving.
         return new ForwardVariableRef(ctx.NAME().getText());
      }
      throw new CompileError("Unhandled symbol " + symbol.toString(program));
   }

   /**
    * Find all comments preceding the passed context.
    * Group the comments into blocks each time an empty line (double newline) is encountered
    *
    * @param ctx The parse context to examine
    * @return The comment blocks preceding the context
    */
   private List<List<Comment>> getCommentBlocks(ParserRuleContext ctx) {
      List<List<Comment>> commentBlocks = new ArrayList<>();
      List<Comment> comments = new ArrayList<>();
      BufferedTokenStream preprocessedTokenStream = cParser.getPreprocessedTokenStream();
      final int startTokenIndex = ctx.start.getTokenIndex();
      if(startTokenIndex < 0)
         return commentBlocks;
      List<Token> hiddenTokens = preprocessedTokenStream.getHiddenTokensToLeft(startTokenIndex);
      if(hiddenTokens != null) {
         for(Token hiddenToken : hiddenTokens) {
            if(hiddenToken.getChannel() == CParser.CHANNEL_WHITESPACE) {
               String text = hiddenToken.getText();
               long newlineCount = text.chars().filter(ch -> ch == '\n').count();
               if(newlineCount > 1 && comments.size() > 0) {
                  // Create new comment block
                  commentBlocks.add(comments);
                  comments = new ArrayList<>();
               }
            } else if(hiddenToken.getChannel() == CParser.CHANNEL_COMMENTS) {
               boolean isBlock = false;
               String text = hiddenToken.getText();
               if(text.startsWith("//")) {
                  text = text.substring(2);
               }
               if(text.startsWith("/*")) {
                  text = text.substring(2, text.length() - 2);
                  isBlock = true;
               }
               Comment comment = new Comment(text);
               comment.setBlock(isBlock);
               comment.setTokenIndex(hiddenToken.getTokenIndex());
               comments.add(comment);
            }
         }
      }
      if(comments.size() > 0) {
         commentBlocks.add(comments);
      }
      return commentBlocks;
   }

   /** Set containing the token index of all comment blocks that have already been used. */
   private final HashSet<Integer> usedCommentTokenIndices = new HashSet<>();

   /**
    * Ensures that the comments have not already been "used" in another context.
    *
    * @param candidate The comments to examine
    * @return The comments if they are unused. An empty comment if they had already been used.
    */
   private List<Comment> ensureUnusedComments(List<Comment> candidate) {
      if(candidate.size() == 0) {
         return candidate;
      }
      int tokenIndex = candidate.get(0).getTokenIndex();
      if(usedCommentTokenIndices.contains(tokenIndex)) {
         // Comment was already used - Return an empty list
         return new ArrayList<>();
      } else {
         // Comment unused - Mark as used and return it
         usedCommentTokenIndices.add(tokenIndex);
         return candidate;
      }
   }

   /**
    * Find the first comments preceding the passed context (search from start until meeting a double newline).
    * Only returns comments if they have not already been "used" by another call.
    *
    * @param ctx The parse context to examine
    * @return The first comments preceding the context
    */
   private List<Comment> getCommentsFile(ParserRuleContext ctx) {
      List<List<Comment>> commentBlocks = getCommentBlocks(ctx);
      if(commentBlocks.size() == 0) {
         return new ArrayList<>();
      }
      return commentBlocks.get(0);
   }

   /**
    * Find comments immediately preceding the passed context (search from end until meeting a double newline)
    * Only returns comments if they have not already been "used" by another call.
    *
    * @param ctx The parse context to examine
    * @return The comments immediately preceding the context
    */
   private List<Comment> getCommentsSymbol(ParserRuleContext ctx) {
      List<List<Comment>> commentBlocks = getCommentBlocks(ctx);
      if(commentBlocks.size() == 0) {
         return new ArrayList<>();
      }
      return commentBlocks.get(commentBlocks.size() - 1);
   }


   private Long evaluateConstantInteger(RValue rValue) {
      if(rValue instanceof ConstantInteger) {
         return ((ConstantInteger) rValue).getValue();
      }
      if(rValue instanceof ConstantValue) {
         try {
            ConstantLiteral lit = ((ConstantValue) rValue).calculateLiteral(program.getScope());
            if(lit instanceof ConstantInteger) {
               return ((ConstantInteger) lit).getValue();
            }
         } catch(Exception e) {
            // fall through
         }
      }
      if(rValue instanceof SymbolVariableRef) {
         Symbol sym = getCurrentScope().findSymbol(((SymbolVariableRef) rValue).getLocalName());
         if(sym == null) {
            sym = program.getScope().findSymbol(((SymbolVariableRef) rValue).getFullName());
         }
         if(sym instanceof Variable && ((Variable) sym).isKindConstant()) {
            RValue initValue = ((Variable) sym).getInitValue();
            if(initValue != null) {
               return evaluateConstantInteger(initValue);
            }
         }
      }
      return null;
   }

   private static class PrePostModifierHandler extends KickCParserBaseVisitor<Void> {

      private final List<PrePostModifier> postMods;
      private final List<PrePostModifier> preMods;
      private final Pass0GenerateStatementSequence mainParser;

      PrePostModifierHandler(Pass0GenerateStatementSequence mainParser) {
         this.mainParser = mainParser;
         preMods = new ArrayList<>();
         postMods = new ArrayList<>();
      }

      static void addPostModifiers(Pass0GenerateStatementSequence parser, ParserRuleContext ctx, StatementSource statementSource) {
         PrePostModifierHandler prePostModifierHandler = new PrePostModifierHandler(parser);
         prePostModifierHandler.visit(ctx);
         List<PrePostModifier> modifiers = prePostModifierHandler.getPostMods();
         addModifierStatements(parser, modifiers, statementSource);
      }

      static void addPreModifiers(Pass0GenerateStatementSequence parser, ParserRuleContext ctx, StatementSource statementSource) {
         PrePostModifierHandler modifierHandler = new PrePostModifierHandler(parser);
         modifierHandler.visit(ctx);
         List<PrePostModifier> modifiers = modifierHandler.getPreMods();
         addModifierStatements(parser, modifiers, statementSource);
      }

      static boolean hasPrePostModifiers(Pass0GenerateStatementSequence parser, ParserRuleContext ctx, StatementSource statementSource) {
         PrePostModifierHandler modifierHandler = new PrePostModifierHandler(parser);
         modifierHandler.visit(ctx);
         return (modifierHandler.getPreMods().size() > 0) || (modifierHandler.getPostMods().size() > 0);
      }

      static boolean hasPostModifiers(Pass0GenerateStatementSequence parser, ParserRuleContext ctx, StatementSource statementSource) {
         PrePostModifierHandler modifierHandler = new PrePostModifierHandler(parser);
         modifierHandler.visit(ctx);
         return modifierHandler.getPostMods().size() > 0;
      }


      private static void addModifierStatements(
            Pass0GenerateStatementSequence parser,
            List<PrePostModifier> modifiers,
            StatementSource source) {
         for(PrePostModifier mod : modifiers) {
            if(mod.bitfieldMember != null) {
               StructMemberRef containerRef = (StructMemberRef) copyLValue(mod.containerLVal);
               RValue currentVal = parser.desugarBitfieldRead(mod.ctx, containerRef, mod.bitfieldMember);
               Operator op = mod.operator.equals(Operators.INCREMENT) ? Operators.PLUS : Operators.MINUS;
               RValue updatedVal = parser.addExprBinary(mod.ctx, currentVal, op, new ConstantInteger(1L));
               parser.writeBitfield(mod.ctx, mod.containerLVal, mod.bitfieldMember, updatedVal);
               continue;
            }
            if(mod.nibbleArrayCtx != null) {
               RValue currentVal = parser.desugarNibbleArrayRead(mod.nibbleArrayCtx);
               Operator op = mod.operator.equals(Operators.INCREMENT) ? Operators.PLUS : Operators.MINUS;
               RValue updatedVal = parser.addExprBinary(mod.ctx, currentVal, op, new ConstantInteger(1L));
               parser.writeNibbleArray(mod.ctx, mod.nibbleArrayCtx, updatedVal);
               continue;
            }
            if(mod.loHiArrayCtx != null) {
               RValue currentVal = parser.desugarLoHiArrayRead(mod.loHiArrayCtx);
               Operator op = mod.operator.equals(Operators.INCREMENT) ? Operators.PLUS : Operators.MINUS;
               RValue updatedVal = parser.addExprBinary(mod.ctx, currentVal, op, new ConstantInteger(1L));
               parser.writeLoHiArray(mod.ctx, mod.loHiArrayCtx, updatedVal);
               continue;
            }
            if(mod.child instanceof ConstantValue) {
               throw new CompileError("Constants can not be modified " + mod.child.toString(), source);
            }
            Statement stmt = new StatementAssignment((LValue) mod.child, mod.operator, copyLValue((LValue) mod.child), false, source, Comment.NO_COMMENTS);
            parser.addStatement(stmt);
            parser.consumeExpr(mod.child);
            if(parser.program.getLog().isVerboseParse()) {
               parser.program.getLog().append("Adding pre/post-modifier " + stmt.toString(parser.program, false));
            }
         }
      }

      List<PrePostModifier> getPreMods() {
         return preMods;
      }

      List<PrePostModifier> getPostMods() {
         return postMods;
      }

      @Override
      public Void visitExprPostMod(KickCParser.ExprPostModContext ctx) {
         Variable bitfieldMember = mainParser.getBitfieldMember(ctx.expr());
         if(bitfieldMember != null) {
            LValue containerLVal = mainParser.getBitfieldContainerLValue(ctx.expr(), bitfieldMember);
            String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
            Operator operator = Operators.getUnary(op);
            PrePostModifier modifier = new PrePostModifier(ctx, operator, bitfieldMember, containerLVal);
            postMods.add(modifier);
            this.visit(ctx.expr());
            return null;
         }
         KickCParser.ExprArrayContext nibbleArrayCtx = mainParser.getNibbleArrayContext(ctx.expr());
         if(nibbleArrayCtx != null) {
            String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
            Operator operator = Operators.getUnary(op);
            PrePostModifier modifier = new PrePostModifier(ctx, operator, nibbleArrayCtx);
            postMods.add(modifier);
            this.visit(ctx.expr());
            return null;
         }
         KickCParser.ExprArrayContext loHiArrayCtx = mainParser.getLoHiArrayContext(ctx.expr());
         if(loHiArrayCtx != null) {
            String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
            Operator operator = Operators.getUnary(op);
            PrePostModifier modifier = new PrePostModifier(ctx, operator, loHiArrayCtx, true);
            postMods.add(modifier);
            this.visit(ctx.expr());
            return null;
         }
         // First handle the ++/-- modifier
         RValue child = (RValue) mainParser.visit(ctx.expr());
         String op = ((TerminalNode) ctx.getChild(1)).getSymbol().getText();
         Operator operator = Operators.getUnary(op);
         PrePostModifier modifier = new PrePostModifier(child, operator);
         postMods.add(modifier);
         // First visit sub-expressions in case they have ++/-- themselves
         this.visit(ctx.expr());
         return null;
      }

      @Override
      public Void visitExprPreMod(KickCParser.ExprPreModContext ctx) {
         Variable bitfieldMember = mainParser.getBitfieldMember(ctx.expr());
         if(bitfieldMember != null) {
            LValue containerLVal = mainParser.getBitfieldContainerLValue(ctx.expr(), bitfieldMember);
            String op = ((TerminalNode) ctx.getChild(0)).getSymbol().getText();
            Operator operator = Operators.getUnary(op);
            PrePostModifier modifier = new PrePostModifier(ctx, operator, bitfieldMember, containerLVal);
            preMods.add(modifier);
            this.visit(ctx.expr());
            return null;
         }
         KickCParser.ExprArrayContext nibbleArrayCtx = mainParser.getNibbleArrayContext(ctx.expr());
         if(nibbleArrayCtx != null) {
            String op = ((TerminalNode) ctx.getChild(0)).getSymbol().getText();
            Operator operator = Operators.getUnary(op);
            PrePostModifier modifier = new PrePostModifier(ctx, operator, nibbleArrayCtx);
            preMods.add(modifier);
            this.visit(ctx.expr());
            return null;
         }
         KickCParser.ExprArrayContext loHiArrayCtx = mainParser.getLoHiArrayContext(ctx.expr());
         if(loHiArrayCtx != null) {
            String op = ((TerminalNode) ctx.getChild(0)).getSymbol().getText();
            Operator operator = Operators.getUnary(op);
            PrePostModifier modifier = new PrePostModifier(ctx, operator, loHiArrayCtx, true);
            preMods.add(modifier);
            this.visit(ctx.expr());
            return null;
         }
         // First handle the ++/-- modifier
         RValue child = (RValue) mainParser.visit(ctx.expr());
         String op = ((TerminalNode) ctx.getChild(0)).getSymbol().getText();
         Operator operator = Operators.getUnary(op);
         PrePostModifier modifier = new PrePostModifier(child, operator);
         preMods.add(modifier);
         // Then visit sub-expressions in case they have ++/--
         this.visit(ctx.expr());
         return null;
      }

      private static class PrePostModifier {
         RValue child;
         public Operator operator;
         ParserRuleContext ctx;
         Variable bitfieldMember;
         LValue containerLVal;
         KickCParser.ExprArrayContext nibbleArrayCtx;
         KickCParser.ExprArrayContext loHiArrayCtx;

         PrePostModifier(RValue child, Operator operator) {
            this.child = child;
            this.operator = operator;
         }

         PrePostModifier(ParserRuleContext ctx, Operator operator, Variable bitfieldMember, LValue containerLVal) {
            this.ctx = ctx;
            this.operator = operator;
            this.bitfieldMember = bitfieldMember;
            this.containerLVal = containerLVal;
         }

         PrePostModifier(ParserRuleContext ctx, Operator operator, KickCParser.ExprArrayContext nibbleArrayCtx) {
            this.ctx = ctx;
            this.operator = operator;
            this.nibbleArrayCtx = nibbleArrayCtx;
         }

         PrePostModifier(ParserRuleContext ctx, Operator operator, KickCParser.ExprArrayContext loHiArrayCtx, boolean isLoHi) {
            this.ctx = ctx;
            this.operator = operator;
            this.loHiArrayCtx = loHiArrayCtx;
         }
      }

   }
}
