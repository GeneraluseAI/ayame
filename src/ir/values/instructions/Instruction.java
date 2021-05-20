package ir.values.instructions;

import ir.Type;
import ir.values.BasicBlock;
import ir.values.User;
import ir.values.Value;

public abstract class Instruction extends User {

  public enum OPcode {
    //todo 加上Opcode
  }

  public Instruction(Type type) {
    super(type);
  }

  BasicBlock parent = null;// 指令所属基本块
  Instruction prev = null;//前驱指令，记住basicBlock中执行流只有一条
  Instruction next = null;//后继指令

  public BasicBlock getParent() {
    return parent;
  }

  public Instruction getPrev() {
    return prev;
  }

  public Instruction getNext() {
    return next;
  }

  public void setParent(BasicBlock parent) {
    this.parent = parent;
  }

  public void setPrev(Instruction prev) {
    this.prev = prev;
  }

  public void setNext(Instruction next) {
    this.next = next;
  }
}