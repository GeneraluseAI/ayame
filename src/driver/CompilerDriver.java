package driver;

import frontend.SysYLexer;
import frontend.SysYParser;
import frontend.Visitor;
import ir.MyModule;

import java.io.File;
import java.io.FileWriter;
import java.util.logging.Logger;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.annotation.Arg;
import net.sourceforge.argparse4j.helper.HelpScreenException;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import pass.PassManager;
import backend.CodeGenManager;

import java.io.IOException;
import util.Mylogger;


/**
 * 编译器驱动部分
 */
public class CompilerDriver {

  /**
   * @param args:从命令行未处理直接传过来的参数
   */
  private static Logger logger;

  public static void run(String[] args) {
    Mylogger.init();
    Config config = Config.getInstance();
    PassManager pm = PassManager.getPassManager();
    ArgumentParser argParser =
        ArgumentParsers.newFor("Compiler").prefixChars("-+").build()
            .description("A compiler for language SysY2021");
    argParser.addArgument("-S", "--source").required(true).action(Arguments.storeTrue());
    argParser.addArgument("-o", "--output").action(Arguments.storeTrue())
        .help("write asm to output-file");
    argParser.addArgument("-f", "--ir").action(Arguments.storeTrue())
        .help("write llvm ir to debug_{$source file name}.ll");
    argParser.addArgument("-d", "--debug").action(Arguments.storeTrue())
        .help("use debug mode , which will record actions in current path");
    //新的可选指令加在上面
    argParser.addArgument("source");
    argParser.addArgument("target");
    try {
      Namespace res = argParser.parseArgs(args);
      Mylogger.init();
      config.setConfig(res);
      logger = Mylogger.getLogger(CompilerDriver.class);
      logger.info("Config -> " + res);
      String source = res.get("source");
      String target = res.get("target");
      CharStream input = CharStreams.fromFileName(source);

      logger.info("Lex begin");
      SysYLexer lexer = new SysYLexer(input);
      lexer.addErrorListener(new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
            int pos, String msg, RecognitionException e) {
          throw new RuntimeException("Lex Error in line: " + line + "pos: " + pos);
        }
      });
      CommonTokenStream tokens = new CommonTokenStream(lexer);
      logger.info("Lex finished");

      logger.info("parse begin");
      SysYParser parser = new SysYParser(tokens);
      parser.setErrorHandler(new BailErrorStrategy());
      ParseTree tree = parser.program();
      logger.info("parse finished");
      logger.info("IR program generating");
      MyModule.getInstance().init();
      Visitor visitor = new Visitor(/* OptionsTable table */);
      visitor.visit(tree);
      logger.info("IR program generated");

      //todo convert to ssa
      pm.runIRPasses(MyModule.getInstance());
      //todo convert to LIR
      if (config.isIRMode) {//做到可以同时使用 -o -f 指令，-o的文件进行底层的优化，-f的文件只进行中高层的优化
        //   pm.addpass(/* llvm ir generate */);
      }
      CodeGenManager cgm=CodeGenManager.getInstance();
      cgm.load(MyModule.getInstance());
      pm.runMCPasses(CodeGenManager.getInstance());
      //todo output
      cgm.MachineCodeGeneration();
      File f =new File(target);
      FileWriter  fw =new FileWriter(f);
      fw.append(cgm.genARM());
      fw.close();
    } catch (HelpScreenException e) {
      //当使用 -h指令时抛出该异常，catch后直接退出。
    } catch (ArgumentParserException e) {
      System.err.println("ArgParsing error,use \"-h\" for more information ");
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
