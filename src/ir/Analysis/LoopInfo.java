package ir.Analysis;

import ir.Loop;
import ir.values.BasicBlock;
import ir.values.Constant;
import ir.values.Constants.ConstantInt;
import ir.values.Function;
import ir.values.Value;
import ir.values.instructions.BinaryInst;
import ir.values.instructions.Instruction;
import ir.values.instructions.Instruction.TAG_;
import ir.values.instructions.MemInst.Phi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Stack;
import util.IList.INode;

public class LoopInfo {

  // map between basic block and the most inner loop
  private HashMap<BasicBlock, Loop> bbLoopMap;
  private ArrayList<Loop> topLevelLoops;
  private ArrayList<Loop> allLoops;

  public LoopInfo() {
    this.bbLoopMap = new HashMap<>();
    this.topLevelLoops = new ArrayList<>();
    this.allLoops = new ArrayList<>();
  }

  public ArrayList<Loop> getTopLevelLoops() {
    return topLevelLoops;
  }

  public ArrayList<Loop> getAllLoops() {
    return allLoops;
  }

  public Loop getLoopForBB(BasicBlock bb) {
    return bbLoopMap.get(bb);
  }

  public Integer getLoopDepthForBB(BasicBlock bb) {
    if (bbLoopMap.get(bb) == null) {
      return 0;
    }
    return bbLoopMap.get(bb).getLoopDepth();
  }

  public Boolean isLoopHeader(BasicBlock bb) {
    // Notice: use `==` instead of `equals` just for speed.
    var loop = bbLoopMap.get(bb);
    if (loop == null) {
      return false;
    }
    return loop.getHeader() == bb;
  }

  // Algorithm: Testing flow graph reducibility, Tarjan
  // https://blog.csdn.net/yeshahayes/article/details/97233940
  // LLVM: LoopInfoImpl.h
  public void computeLoopInfo(Function function) {
    DomInfo.computeDominanceInfo(function);

    BasicBlock entry = function.getList_().getEntry().getVal();
    Stack<BasicBlock> postOrderStack = new Stack<>();
    Stack<BasicBlock> backEdgeTo = new Stack<>();
    ArrayList<BasicBlock> postOrder = new ArrayList<>();
    this.bbLoopMap = new HashMap<>();
    this.topLevelLoops = new ArrayList<>();
    this.allLoops = new ArrayList<>();

    postOrderStack.push(entry);
    BasicBlock curr;
    while (!postOrderStack.isEmpty()) {
      curr = postOrderStack.pop();
      postOrder.add(curr);
      for (BasicBlock child : curr.getIdoms()) {
        postOrderStack.push(child);
      }
    }
    Collections.reverse(postOrder);

    for (BasicBlock header : postOrder) {
      for (BasicBlock pred : header.getPredecessor_()) {
        if (pred.getDomers().contains(header)) {
          backEdgeTo.push(pred);
        }
      }

      if (!backEdgeTo.isEmpty()) {
        Loop loop = new Loop(header);
        while (!backEdgeTo.isEmpty()) {
          BasicBlock pred = backEdgeTo.pop();
          Loop subloop = getLoopForBB(pred);
          if (subloop == null) {
            bbLoopMap.put(pred, loop);
            if (pred == loop.getHeader()) {
              continue;
            }

            for (BasicBlock predPred : pred.getPredecessor_()) {
              backEdgeTo.push(predPred);
            }
          } else {
            while (subloop.getParentLoop() != null) {
              subloop = subloop.getParentLoop();
            }
            if (subloop == loop) {
              continue;
            }

            subloop.setParentLoop(loop);
            for (BasicBlock subHeaderPred : subloop.getHeader().getPredecessor_()) {
              Loop tmp = bbLoopMap.get(subHeaderPred);
              if (tmp == null || !tmp.equals(subloop)) {
                backEdgeTo.push(subHeaderPred);
              }
            }
          }
        }
      }
    }

    for (INode<BasicBlock, Function> bbNode : function.getList_()) {
      bbNode.getVal().setDirty(false);
    }
    populateLoopsDFS(entry);

    computeAllLoops();
    reorderLoops(function);
    computeAllLoops();
  }

  public void computeAdditionalLoopInfo() {
    for (var loop : allLoops) {
      loop.setIndVarInit(null);
      loop.setIndVar(null);
      loop.setIndVarEnd(null);
      loop.setStepInst(null);
      loop.setStep(null);
      loop.getExitingBlocks().clear();
    }

    computeExitingBlocks();
    computeExitBlocks();
    computeLatchBlocks();
    // 只计算 isSimpleForLoop() == true 的循环的 indVarInfo
    computeIndVarInfo();
  }

  public void populateLoopsDFS(BasicBlock bb) {
    if (bb.isDirty()) {
      return;
    }
    bb.setDirty(true);

    for (BasicBlock succBB : bb.getSuccessor_()) {
      populateLoopsDFS(succBB);
    }

    Loop subLoop = getLoopForBB(bb);
    if (subLoop != null && bb == subLoop.getHeader()) {
      if (subLoop.getParentLoop() != null) {
        subLoop.getParentLoop().getSubLoops().add(subLoop);
      } else {
        topLevelLoops.add(subLoop);
      }

      // 反转 subLoop.getBlocks[1, size - 1]
      Collections.reverse(subLoop.getBlocks());
      subLoop.getBlocks().add(0, bb);
      subLoop.getBlocks().remove(subLoop.getBlocks().size() - 1);

      Collections.reverse(subLoop.getSubLoops());
      subLoop = subLoop.getParentLoop();
    }

    while (subLoop != null) {
      subLoop.getBlocks().add(bb);
      subLoop = subLoop.getParentLoop();
    }
  }

  private static class LoopOrderComparator implements Comparator<Loop> {

    @Override
    public int compare(Loop o1, Loop o2) {
      int header = o1.getLoopHeader().getDomLevel().compareTo(o2.getLoopHeader().getDomLevel());
      return header != 0 ? header : 1 - o1.getLoopDepth().compareTo(o2.getLoopDepth());
    }
  }

  public void reorderLoops(Function function) {
    this.topLevelLoops.sort(new LoopOrderComparator());
    for (var loop : allLoops) {
      if (loop.getSubLoops() != null && !loop.getSubLoops().isEmpty()) {
        loop.getSubLoops().sort(new LoopOrderComparator());
      }
    }
  }

  private void computeAllLoops() {
    this.allLoops = new ArrayList<>();
    Stack<Loop> loopStack = new Stack<>();
    allLoops.addAll(topLevelLoops);
    loopStack.addAll(topLevelLoops);
    while (!loopStack.isEmpty()) {
      var loop = loopStack.pop();
      if (!loop.getSubLoops().isEmpty()) {
        allLoops.addAll(loop.getSubLoops());
        loopStack.addAll(loop.getSubLoops());
      }
    }
  }

  private void computeExitingBlocks() {
    for (var loop : allLoops) {
      for (var bb : loop.getBlocks()) {
        var inst = bb.getList().getLast().getVal();
        if (inst.tag == Instruction.TAG_.Br && inst.getNumOP() == 3) {
          BasicBlock bb1 = (BasicBlock) inst.getOperands().get(1);
          BasicBlock bb2 = (BasicBlock) inst.getOperands().get(2);
          if (!loop.getBlocks().contains(bb1) || !loop.getBlocks().contains(bb2)) {
            loop.getExitingBlocks().add(bb);
          }
        }
      }
    }
  }

  private void computeExitBlocks() {
    for (var loop : allLoops) {
      for (var bb : loop.getBlocks()) {
        for (var succBB : bb.getSuccessor_()) {
          if (!loop.getBlocks().contains(succBB)) {
            loop.getExitBlocks().add(succBB);
          }
        }
      }
    }
  }

  private void computeLatchBlocks() {
    for (var loop : allLoops) {
      for (var predbb : loop.getLoopHeader().getPredecessor_()) {
        if (loop.getBlocks().contains(predbb)) {
          loop.getLatchBlocks().add(predbb);
        }
      }
    }
  }

  // 计算索引 phi、索引初值、索引迭代指令
  // FIXME: 只是 trivial 的做法，latchCmpInst 操作数中循环深度和 latchCmpInst 不同的就是 stepInst，按照这个来，精确一点的需要 SCEV
  private void computeIndVarInfo() {
    for (var loop : allLoops) {
      var latchCmpInst = loop.getLatchCmpInst();
      if (!loop.isSimpleForLoop() || latchCmpInst == null) {
        continue;
      }

      var header = loop.getLoopHeader();

      // indVarCondInst
      for (var i = 0; i <= 1; i++) {
        var op = latchCmpInst.getOperands().get(i);
        if (op instanceof Instruction) {
          Instruction opInst = (Instruction) op;
          if (!getLoopDepthForBB(opInst.getBB()).equals(getLoopDepthForBB(latchCmpInst.getBB()))) {
            loop.setIndVarCondInst(
                (Instruction) latchCmpInst.getOperands().get(1 - i));
            loop.setIndVarEnd(latchCmpInst.getOperands().get(i));
          } else {
            loop.setIndVarCondInst(
                (Instruction) latchCmpInst.getOperands().get(i));
            loop.setIndVarEnd(latchCmpInst.getOperands().get(1 - i));
          }
        } else {
          loop.setIndVarCondInst(
              (Instruction) latchCmpInst.getOperands().get(1 - i));
          loop.setIndVarEnd(latchCmpInst.getOperands().get(i));
        }
      }

      if (loop.getIndVarCondInst() == null || (!(loop.getIndVarCondInst() instanceof BinaryInst))) {
        return;
      }

      // indVar
      // 假设 indVarCondInst = Binary (indVar, OP2)，且 indVar 是 phi
      var indVarCondInst = loop.getIndVarCondInst();
      Value compareBias = null;
      for (var op : indVarCondInst.getOperands()) {
        if (op instanceof Phi) {
          loop.setIndVar((Phi) op);
        } else {
          compareBias = op;
        }
      }
      assert compareBias != null;

      if (loop.getIndVar() == null) {
        return;
      }

      // indVarInit, stepInst
      var indVar = loop.getIndVar();
      int indVarDepth = this.getLoopDepthForBB(indVar.getBB());
      for (var incomingVal : indVar.getIncomingVals()) {
        if (incomingVal instanceof Instruction) {
          Instruction inst = (Instruction) incomingVal;
          int incomingDepth = this.getLoopDepthForBB(inst.getBB());
          if (indVarDepth != incomingDepth) {
            loop.setIndVarInit(incomingVal);
          } else {
            loop.setStepInst((Instruction) incomingVal);
          }
        } else {
          loop.setIndVarInit(incomingVal);
        }
      }

      // step
      // 假设 stepInst = Binary (indVar, step)
      var stepInst = loop.getStepInst();
      if (stepInst == null) {
        return;
      }
      for (var op : stepInst.getOperands()) {
        if (op != indVar) {
          loop.setStep(op);
        }
      }

      // old method to find indvar and indVarInit
//      for (var instNode : header.getList()) {
//        var inst = instNode.getVal();
//        if (!(inst instanceof Phi)) {
//          break;
//        }
//
//        var phi = (Phi) inst;
//        // indVar and indVarInit
//        if (phi.getOperands().contains(stepInst)) {
//          loop.setIndVar(phi);
//          var stepIndex = phi.getOperands().indexOf(stepInst);
//          loop.setIndVarInit(phi.getOperands().get(1 - stepIndex));
//          break;
//        }
//      }

      // old method to find step
//      var indVarIndex = stepInst.getOperands().indexOf(loop.getIndVar());
//      if (indVarIndex == -1) {
//        return;
//      }
//      loop.setStep(stepInst.getOperands().get(1 - indVarIndex));

      // tripCount
      if (stepInst.tag == TAG_.Add &&
          loop.getStep() instanceof Constant &&
          loop.getIndVarInit() instanceof Constant &&
          loop.getIndVarEnd() instanceof Constant &&
          compareBias instanceof Constant) {
        var init = ((ConstantInt) loop.getIndVarInit()).getVal();
        var end = ((ConstantInt) loop.getIndVarEnd()).getVal();
        var step = ((ConstantInt) loop.getStep()).getVal();
        var bias = ((ConstantInt) compareBias).getVal();
        int tripCount = (int) (1e9 + 7); //

        // 只考虑 Lt, Le, Gt, Ge, Ne
        // Lt, Gt: |end - init| / |step|
        // Le, Ge: (|end - init| + 1) / |step|
        // Ne: |end - init| / |step| (有余数时则为 inf)
        switch (latchCmpInst.tag) {
          case Lt -> {
            if (step > 0) {
              tripCount = init < end ? ceilDiv(end - init, step) : 0;
            }
          }
          case Gt -> {
            if (step < 0) {
              tripCount = init > end ? ceilDiv(init - end, -step) : 0;
            }
          }
          case Le -> {
            if (step > 0) {
              tripCount = init <= end ? ceilDiv(end - init + 1, step) : 0;
            }
          }
          case Ge -> {
            if (step < 0) {
              tripCount = init >= end ? ceilDiv(init - end + 1, -step) : 0;
            }
          }
          case Ne -> {
            if (end - init == 0) {
              tripCount = 0;
            } else if (step * (end - init) > 0 && (end - init) % step == 0) {
              tripCount = (end - init) / step;
            }
          }
        }

        tripCount -= (bias - step);

        loop.setTripCount(tripCount);
      }
    }
  }

  private int ceilDiv(int a, int b) {
    return (int) Math.ceil((double) a / b);
  }

  public HashMap<BasicBlock, Loop> getBbLoopMap() {
    return bbLoopMap;
  }

  public void addBBToLoop(BasicBlock bb, Loop loop) {
    if (loop == null) {
      return;
    }

    this.bbLoopMap.put(bb, loop);
    loop.addBlock(bb);
  }

  public void removeBBFromAllLoops(BasicBlock bb) {
    var loop = getLoopForBB(bb);
    while (loop != null) {
      loop.removeBlock(bb);
      loop = loop.getParentLoop();
    }
    this.bbLoopMap.remove(bb);
  }

  public void removeTopLevelLoop(Loop loop) {
    this.topLevelLoops.remove(loop);
  }

  public void addTopLevelLoop(Loop loop) {
    this.topLevelLoops.add(loop);
  }

  public void removeLoop(Loop loop) {
    ArrayList<BasicBlock> loopBlocks = new ArrayList<>();
    loopBlocks.addAll(loop.getBlocks());
    if (loop.getParentLoop() != null) {
      var parentLoop = loop.getParentLoop();
      for (var bb : loopBlocks) {
        if (this.getLoopForBB(bb) == loop) {
          this.getBbLoopMap().put(bb, parentLoop);
        }
      }

      parentLoop.removeSubLoop(loop);

      while (loop.getSubLoops().size() != 0) {
        var subLoop = loop.getSubLoops().get(0);
        loop.removeSubLoop(subLoop);
        parentLoop.addSubLoop(subLoop);
      }
    } else {
      for (var bb : loopBlocks) {
        if (this.getLoopForBB(bb) == loop) {
          // bb 在最外层循环里了
          this.removeBBFromAllLoops(bb);
        }
      }

      this.removeTopLevelLoop(loop);
      while (loop.getSubLoops().size() != 0) {
        var subLoop = loop.getSubLoops().get(0);
        loop.removeSubLoop(subLoop);
        this.addTopLevelLoop(subLoop);
      }
    }
  }

}
