package ir;

import ir.values.Function;
import ir.values.GlobalVariable;
import ir.values.instructions.Instruction;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * IR结构中的顶层container,存有函数，全局变量，符号表，指令的list以及其他所有需要的信息 由于SysY只需要支持单文件编译，所以Module事实上是以单例存在的
 */
public class MyModule {

  public static MyModule getInstance() {
    return myModule;
  }

  public void addGlobalVariable(GlobalVariable gl) {
    __globalVariables.add(gl);
  }

  public void addFunction(Function fn) {
    __functions.add(fn);
  }

  public void addInstruction(Instruction inst) {
    __instructions.put(inst.getHandle(), inst);
  }

  public ArrayList<GlobalVariable> __globalVariables;
  public ArrayList<Function> __functions;
  public HashMap<Integer, Instruction> __instructions;
  private static final MyModule myModule = new MyModule();

  private MyModule() {
  }

}
