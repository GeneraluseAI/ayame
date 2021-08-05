package ir.Analysis;

import ir.Loop;
import ir.values.BasicBlock;
import ir.values.Function;
import ir.values.instructions.Instruction;
import ir.values.instructions.MemInst.Phi;
import util.IList.INode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Stack;

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
//    computeAdditionalLoopInfo();
  }

  public void computeAdditionalLoopInfo() {
    for (var loop: allLoops) {
      loop.setIndVarInit(null);
      loop.setIndVar(null);
      loop.setLatchBlock(null);
      loop.setStepInst(null);
      loop.getExitingBlocks().clear();
    }

    computeExitingBlocks();
    computeLatchBlock();
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

  private void computeAllLoops() {
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
          var bb1 = inst.getOperands().get(1);
          var bb2 = inst.getOperands().get(2);
          if (!loop.getBlocks().contains(bb1) || !loop.getBlocks().contains(bb2)) {
            loop.getExitingBlocks().add(bb);
          }
        }
      }
    }
  }

  private void computeLatchBlock() {
    for (var loop : allLoops) {
      if (!loop.isCanonical()) {
        continue;
      }
      for (var predbb : loop.getLoopHeader().getPredecessor_()) {
        if (loop.getBlocks().contains(predbb)) {
          loop.setLatchBlock(predbb);
        }
      }
    }
  }

  // 计算索引 phi、索引初值、索引迭代指令
  // FIXME: 只是 trivial 的做法，latchCmpInst 操作数中循环深度和 latchCmpInst 不同的就是 stepInst，按照这个来，精确一点的需要 SCEV
  private void computeIndVarInfo() {
    for (var loop : allLoops) {
      var latchCmpInst = loop.getLatchCmpInst();
      if (!loop.isCanonical() || latchCmpInst == null) {
        continue;
      }

      var header = loop.getLoopHeader();

      // stepInst
      for (var i = 0; i <= 1; i++) {
        var op = latchCmpInst.getOperands().get(i);
        if (op instanceof Instruction) {
          Instruction opInst = (Instruction) op;
          if (getLoopDepthForBB(opInst.getBB()) != getLoopDepthForBB(latchCmpInst.getBB())) {
            loop.setStepInst(
                (Instruction) latchCmpInst.getOperands().get(1 - i));
            loop.setIndVarEnd(latchCmpInst.getOperands().get(i));
          } else {
            loop.setStepInst(
                (Instruction) latchCmpInst.getOperands().get(i));
            loop.setIndVarEnd(latchCmpInst.getOperands().get(1 - i));
          }
        } else {
          loop.setStepInst(
              (Instruction) latchCmpInst.getOperands().get(1 - i));
          loop.setIndVarEnd(latchCmpInst.getOperands().get(i));
        }
      }

      if (loop.getStepInst() == null) {
        return;
      }

      var stepInst = loop.getStepInst();
      for (var instNode : header.getList()) {
        var inst = instNode.getVal();
        if (!(inst instanceof Phi)) {
          break;
        }

        var phi = (Phi) inst;
        // indVar and indVarInit
        if (phi.getOperands().contains(stepInst)) {
          loop.setIndVar(phi);
          var stepIndex = phi.getOperands().indexOf(stepInst);
          loop.setIndVarInit(phi.getOperands().get(1 - stepIndex));
          break;
        }
      }
    }
  }
}
