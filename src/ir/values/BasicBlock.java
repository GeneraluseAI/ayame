package ir.values;

import ir.types.Type;
import ir.types.Type.LabelType;
import ir.values.instructions.Instruction;

import java.util.ArrayList;
import util.IList;
import util.IList.INode;

/**
 * container基本块，func持有bb，bb不持有inst， 只持有inst的引用，inst的引用放在一个链表里 Basic blocks are Valuesbecause they
 * are referenced by instructions such as branch
 * <p>
 * The type of a BasicBlock is "Type::LabelTy" because the basic block represents a label to which a
 * branch can jump.
 */
public class BasicBlock extends Value {


  public BasicBlock(String name) {
    super(name, LabelType.getType());
    //当我们新建一个BasicBlock对象的时候实际上很快就要对其进行操作了，
    //所以这里直接new了2个container
    this.predecessor_ = new ArrayList<>();
    this.successor_ = new ArrayList<>();
    list_ = new IList<>(this);
    node_ = new INode<>(this);
  }

  //插入到parent的末尾
  public BasicBlock(String name, Function parent) {
    super(name, LabelType.getType());
    this.predecessor_ = new ArrayList<>();
    this.successor_ = new ArrayList<>();
    list_ = new IList<>(this);
    node_ = new INode<>(this);
    this.node_.insertAtEnd(parent.getList_());
  }

  public Function getParent() {
    return parent;
  }

  public ArrayList<BasicBlock> getPredecessor_() {
    return predecessor_;
  }

  public ArrayList<BasicBlock> getSuccessor_() {
    return successor_;
  }

  public IList<Instruction, BasicBlock> getList() {
    return list_;
  }

  private INode<BasicBlock, Function> node_;
  private IList<Instruction, BasicBlock> list_; //在well form的bb里面,最后一个listNode是terminator
  protected Function parent;//它所属的函数
  protected ArrayList<BasicBlock> predecessor_;//前驱
  protected ArrayList<BasicBlock> successor_;//后继
}
