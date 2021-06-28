package ir.values;

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

  public BasicBlock getIdomer() {
    return idomer;
  }

  public ArrayList<BasicBlock> getIdoms() {
    return idoms;
  }

  public ArrayList<BasicBlock> getDomers() {
    return domers;
  }

  public void setIdomer(BasicBlock idomer) {
    this.idomer = idomer;
  }

  public void setIdoms(ArrayList<BasicBlock> idoms) {
    this.idoms = idoms;
  }

  public void setDomers(ArrayList<BasicBlock> domers) {
    this.domers = domers;
  }

  public boolean isDirty() {
    return dirty;
  }

  public void setDirty(boolean dirty) {
    this.dirty = dirty;
  }

  public ArrayList<BasicBlock> getDominanceFrontier() {
    return dominanceFrontier;
  }

  private INode<BasicBlock, Function> node_;
  private IList<Instruction, BasicBlock> list_; //在well form的bb里面,最后一个listNode是terminator
  protected Function parent;//它所属的函数
  protected ArrayList<BasicBlock> predecessor_;//前驱
  protected ArrayList<BasicBlock> successor_;//后继

  private boolean dirty; // 在一些对基本块的遍历中，表示已经遍历过



  // domination info
  // FIXME maybe change `ArrayList` to `HashSet` is better.
  protected BasicBlock idomer;  // 直接支配节点
  protected ArrayList<BasicBlock> idoms; // 直接支配的节点集
  protected ArrayList<BasicBlock> domers; // 支配者节点集
  protected ArrayList<BasicBlock> dominanceFrontier;
}
