package backend;

import backend.machinecodes.*;
import backend.reg.MachineOperand;
import backend.reg.VirtualReg;
import ir.MyModule;
import ir.types.ArrayType;
import ir.types.IntegerType;
import ir.types.PointerType;
import ir.types.Type;
import ir.values.*;
import ir.values.instructions.BinaryInst;
import ir.values.instructions.Instruction;
import ir.values.instructions.MemInst;
import ir.values.instructions.TerminatorInst;
import util.IList;
import util.IList.INode;
import ir.values.instructions.MemInst.Phi;
import util.Mylogger;
import backend.machinecodes.ArmAddition.CondType;
import util.Pair;

import javax.swing.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * 后端的顶层模块，管理整个后端的流程，
 */
public class CodeGenManager {

    // all functions
    private ArrayList<MachineFunction> machineFunctions = new ArrayList<>();

    //key为phi指令的目标vr，value为一串01序列，长度等同于phi指令所在基本块的前驱块，如果有环该位为0
    private HashMap<VirtualReg, ArrayList<Boolean>> phiRows = new HashMap<>();

    //ir moudle
    private static MyModule myModule;

    private static Logger logger;

    private CodeGenManager() {
        logger = Mylogger.getLogger(CodeGenManager.class);
    }

    public void load(MyModule m) {
        myModule = m;
    }

    private static CodeGenManager codeGenManager;

    public static boolean canEncodeImm(int imm) {
        int n = imm;
        for (int ror = 0; ror < 32; ror += 2) {
            if ((n & ~0xFF) == 0) {
                return true;
            }
            n = (n << 2) | (n >>> 30);
        }
        return false;
    }

    private MachineOperand genImm(int imm, MachineBlock mb) {
        MachineOperand mo = new MachineOperand(imm);
        if (canEncodeImm(imm)) {
            return mo;
        } else {
            VirtualReg vr = new VirtualReg();
            mb.getMF().addVirtualReg(vr);
            MachineCode mc = new MCMove(mb);
            ((MCMove) mc).setDst(vr);
            ((MCMove) mc).setRhs(mo);
            return vr;
        }
    }

    //ir->machinecode
    public static CodeGenManager getInstance() {
        if (codeGenManager == null) {
            codeGenManager = new CodeGenManager();
        }
        return codeGenManager;
    }

    public ArrayList<MachineFunction> getMachineFunctions() {
        return machineFunctions;
    }

    interface HandlePhi {
        void handlephi();
    }

    interface AnalyzeNoImm {
        MachineOperand analyzeNoImm(Value v, MachineBlock mb);
    }

    interface DFSSerialize {
        void dfsSerialize();
    }

    public void dfsSerial(MachineBlock mb, MachineFunction mf, HashMap<MachineBlock, Boolean> isVisit) {
        isVisit.put(mb, true);
        mf.insertBlock(mb);
        if (mb.getTrueSucc() == null && mb.getFalseSucc() == null) {
            return;
        }
        //只有一个后继的情况
        Pair<MachineBlock, MachineBlock> truePair = new Pair<>(mb, mb.getTrueSucc());
        Pair<MachineBlock, MachineBlock> falsePair = new Pair<>(mb, mb.getFalseSucc());
        if (mb.getFalseSucc() == null) {
            //当前基本块只有一个后继，且后继一定排在当前基本块之后，那么跳转指令就是废的
            MachineCode mcl = mb.getmclist().getLast().getVal();
            assert (mcl instanceof MCJump);
            mb.getmclist().getLast().removeSelf();
            //如果waiting中有copy，则插入当前块之后，即两个块中间
            if (waiting.containsKey(truePair) && !waiting.get(truePair).isEmpty()) {
                Iterator<MachineCode> mcIte = waiting.get(truePair).iterator();
                while (mcIte.hasNext()) {
                    MachineCode mci = mcIte.next();
                    mci.setMb(mb);
                }
            }
            if (isVisit.containsKey(mb.getTrueSucc())) {
                mb.addAtEndMC(mcl.getNode());
            } else {
                dfsSerial(mb.getTrueSucc(), mf, isVisit);
            }
        } else {
            //如果false后继已经在mblist中而true后继不在，那交换位置，尽量让false块在序列化的下一个
            if (isVisit.containsKey(mb.getFalseSucc()) && !isVisit.containsKey(mb.getTrueSucc())) {
                MachineBlock temp = mb.getFalseSucc();
                mb.setFalseSucc(mb.getTrueSucc());
                mb.setTrueSucc(temp);
                assert (mb.getmclist().getLast().getVal() instanceof MCBranch);
                CondType cond = mb.getmclist().getLast().getVal().getCond();
                ((MCBranch) mb.getmclist().getLast().getVal()).setCond(getOppoCond(cond));
                ((MCBranch) mb.getmclist().getLast().getVal()).setTarget(mb.getTrueSucc());
            }
            //处理True后继
            if (waiting.containsKey(truePair) && !waiting.get(truePair).isEmpty()) {
                //如果true块只有一个前驱基本块，那么就可以把waiting中的copy插入true块的最前面
                if (mb.getTrueSucc().getPred().size() == 1) {
                    Iterator<MachineCode> mcIte = waiting.get(truePair).iterator();
                    while (mcIte.hasNext()) {
                        MachineCode mci = mcIte.next();
                        mci.insertBeforeNode(mb.getTrueSucc().getmclist().getEntry().getVal());
                    }
                } else {
                    //如果true块有多个前驱块，那么只能在当前块和true块之间新建一个块插入waiting中的copy
                    MachineBlock newMB = new MachineBlock(mf);
                    Iterator<MachineCode> mcIte = waiting.get(truePair).iterator();
                    while (mcIte.hasNext()) {
                        MachineCode mci = mcIte.next();
                        mci.setMb(newMB);
                    }
                    MCJump jump = new MCJump(newMB);
                    jump.setTarget(mb.getTrueSucc());
                    assert (mb.getTrueSucc().getPred().contains(mb));
                    mb.getTrueSucc().removePred(mb);
                    mb.getTrueSucc().addPred(newMB);
                    newMB.setTrueSucc(mb.getTrueSucc());
                    newMB.addPred(mb);
                    mb.setTrueSucc(newMB);
                    MCBranch br = (MCBranch) (mb.getmclist().getLast().getVal());
                    br.setTarget(mb.getTrueSucc());
                }


            }

            //如果此时false块依然在mblist中，那么说明两个块都在mblist中
            if (isVisit.containsKey(mb.getFalseSucc())) {
                if (waiting.containsKey(falsePair) && !waiting.get(falsePair).isEmpty()) {
                    MachineBlock newMB = new MachineBlock(mf);
                    Iterator<MachineCode> mcIte = waiting.get(falsePair).iterator();
                    while (mcIte.hasNext()) {
                        MachineCode mci = mcIte.next();
                        mci.setMb(newMB);
                    }
                    MCJump jump = new MCJump(newMB);
                    jump.setTarget(mb.getFalseSucc());
                    assert (mb.getFalseSucc().getPred().contains(mb));
                    mb.getFalseSucc().removePred(mb);
                    mb.getFalseSucc().addPred(newMB);
                    newMB.setTrueSucc(mb.getFalseSucc());
                    newMB.addPred(mb);
                    mb.setFalseSucc(newMB);
                }
                //如果两个后继块都已经在mbList中，本基本块的最后一条指令跳转到True块，还缺一条跳转到False块的指令，加到最后
                MCJump jump = new MCJump(mb);
                jump.setTarget(mb.getFalseSucc());

            } else {
                //如果false块没有被访问过，那么就放在当前块后面，当前块的br(跳向true块)指令后，
                //false块之前可以插入waiting中的copy指令
                if (waiting.containsKey(falsePair) && !waiting.get(falsePair).isEmpty()) {
                    Iterator<MachineCode> mcIte = waiting.get(falsePair).iterator();
                    while (mcIte.hasNext()) {
                        MachineCode mci = mcIte.next();
                        mci.setMb(mb);
                    }
                }
            }
            //以上过程可能修改本基本块的True后继/False后继，所以重新判断是否被访问过
            if (!isVisit.containsKey(mb.getFalseSucc())) {
                dfsSerial(mb.getFalseSucc(), mf, isVisit);
            }
            if (!isVisit.containsKey(mb.getTrueSucc())) {
                dfsSerial(mb.getTrueSucc(), mf, isVisit);
            }
        }
    }

    private void fixStack(MachineFunction mf) {
        var useRegs = mf.getUsedRegIdxs();
        for (int idx : useRegs) {
            for (int i = 4; i <= 12; i++) {
                if (i == idx) {
                    mf.getUsedSavedRegs().add(mf.getPhyReg(i));
                }
            }
            if (idx == 14) {
                mf.getUsedSavedRegs().add(mf.getPhyReg(14));
                mf.setUsedLr(true);
            }
        }
        int regs = mf.getUsedSavedRegs().size() + (mf.isUsedLr() ? 1 : 0);
        mf.getArgMoves().forEach(mv -> {
            assert (mv instanceof MCMove);
            assert (mv.getRhs().getState() == MachineOperand.state.imm);
            mv.setRhs(new MachineOperand(mv.getRhs().getImm() + mf.getStackSize() + 4 * regs));
        });

    }


    public String genARM() {
        String arm = "";
        arm += ".arch armv7ve\n";
        arm += ".text\n";
        Iterator<MachineFunction> mfIte = machineFunctions.iterator();
        while (mfIte.hasNext()) {
            MachineFunction mf = mfIte.next();
            fixStack(mf);
            arm += "\n.global\t";
            arm += mf.getName() + "\n";
            arm += mf.getName() + ":\n";
            StringBuilder sb = new StringBuilder();
            mf.getUsedSavedRegs().forEach(phyReg -> {
                sb.append(phyReg.getName());
                sb.append(", ");
            });
            if (mf.isUsedLr() || !mf.getUsedSavedRegs().isEmpty()) {
                String s = sb.toString();
                int l = s.lastIndexOf(',');
                s = s.substring(0, l);
                arm += "\tpush\t{";
                arm += s;
                arm += "}\n";
            }
            if (mf.getStackSize() != 0) {
                String op = canEncodeImm(-mf.getStackSize()) ? "add" : "sub";
                MachineOperand v1 = canEncodeImm(-mf.getStackSize()) ? new MachineOperand(-mf.getStackSize()) : new MachineOperand(mf.getStackSize());
                if (canEncodeImm(mf.getStackSize()) || canEncodeImm(-mf.getStackSize())) {
                    arm += op;
                    arm += "\tsp, sp, " + v1.getName() + "\n";
                } else {
                    MCMove mv = new MCMove();
                    mv.setRhs(v1);
                    mv.setDst(mf.getPhyReg("r5"));
                    arm += mv.toString();
                    arm += op + "\tsp,\tsp,\t" + mf.getPhyReg(5).getName() + "\n";
                }
            }
            Iterator<INode<MachineBlock, MachineFunction>> mbIte = mf.getmbList().iterator();
            while (mbIte.hasNext()) {
                INode<MachineBlock, MachineFunction> mbNode = mbIte.next();
                MachineBlock mb = mbNode.getVal();
                arm += mb.getName() + ":\n";
                arm += "@predBB:";
                StringBuilder sb1 = new StringBuilder();
                if (mb.getPred() != null) {
                    mb.getPred().forEach(p -> {
                        sb1.append(p.getName());
                        sb1.append(" ");
                    });
                }
                arm += sb1.toString() + "\n";
                if (mb.getTrueSucc() != null) {
                    arm += "@trueSucc: " + mb.getTrueSucc().getName() + "\n";
                }
                if (mb.getFalseSucc() != null) {
                    arm += "@falseSucc: " + mb.getFalseSucc().getName() + "\n";
                }
                Iterator<INode<MachineCode, MachineBlock>> mcIte = mb.getmclist().iterator();
                while (mcIte.hasNext()) {
                    INode<MachineCode, MachineBlock> mcNode = mcIte.next();
                    MachineCode mc = mcNode.getVal();
                    arm += mc.toString();
                }
                arm += "\n";
            }
            arm += "\n";
        }
        ArrayList<GlobalVariable> gVs = myModule.__globalVariables;
        if (!gVs.isEmpty()) {
            arm += "\n\n.data\n";
            arm += ".align 4\n";
        }
        for (GlobalVariable gv : gVs) {
            assert irMap.containsKey(gv);
            arm += ".global\t" + irMap.get(gv).getName() + "\n";
            arm += irMap.get(gv).getName() + ":\n";
            PointerType p = (PointerType) gv.getType();
            if (p.getContained() instanceof IntegerType) {
                arm += "\t.word\t";
                assert (gv.init != null);
                arm += ((Constants.ConstantInt) gv.init).getVal();
                arm += "\n";
            } else {
                assert (p.getContained() instanceof ArrayType);
                if (gv.init == null) {
                    int n = 1;
                    ArrayList<Integer> dims = ((ArrayType) p.getContained()).getDims();
                    for (Integer d : dims) {
                        n *= d;
                    }
                    arm += "\t.fill\t" + n + ",\t4,\t0\n";
                } else {
                    ArrayList<Constant> initValues = ((Constants.ConstantArray) gv.init).getConst_arr_();
                    int lastv = ((Constants.ConstantInt) initValues.get(0)).getVal();
                    int count = 0;
                    for (Constant c : initValues) {
                        int v = ((Constants.ConstantInt) c).getVal();
                        if (v == lastv) {
                            count++;
                        } else {
                            if (count == 1) {
                                arm += "\t.word\t" + lastv + "\n";
                            } else {
                                arm += "\t.fill\t" + count + ",\t4,\t" + lastv + "\n";
                            }
                            lastv = v;
                            count = 1;
                        }
                    }
                    if (count == 1) {
                        arm += "\t.word\t" + lastv + "\n";
                    } else {
                        arm += "\t.fill\t" + count + ",\t4,\t" + lastv + "\n";
                    }
                }

            }
        }
        return arm;
    }

    HashMap<Value, VirtualReg> irMap = new HashMap<>();

    public void MachineCodeGeneration() {
        ArrayList<GlobalVariable> gVs = myModule.__globalVariables;
        logger.info("CodeGeneration begin");
        Iterator<GlobalVariable> itgVs = gVs.iterator();
        while (itgVs.hasNext()) {
            GlobalVariable gV = itgVs.next();
            VirtualReg gVr = new VirtualReg(gV.getName(), true);
            irMap.put(gV, gVr);
        }
        IList<Function, MyModule> fList = myModule.__functions;
        Iterator<INode<Function, MyModule>> fIt = fList.iterator();
        HashMap<Function, MachineFunction> fMap = new HashMap<>();
        while (fIt.hasNext()) {
            Function f = fIt.next().getVal();
            MachineFunction mf = new MachineFunction(this, f.getName());
            fMap.put(f, mf);
        }
        fIt = fList.iterator();
        while (fIt.hasNext()) {
            INode<Function, MyModule> fNode = fIt.next();
            f = fNode.getVal();
            mf = fMap.get(f);
            if (f.isBuiltin_()) {
                continue;
            }
            machineFunctions.add(mf);
            IList<BasicBlock, Function> bList = f.getList_();
            Iterator<INode<BasicBlock, Function>> bIt = bList.iterator();
            while (bIt.hasNext()) {
                INode<BasicBlock, Function> bNode = bIt.next();
                bMap.put(bNode.getVal(), new MachineBlock(mf));
            }
            bIt = bList.iterator();
            while (bIt.hasNext()) {
                BasicBlock b = bIt.next().getVal();
                MachineBlock mb = bMap.get(b);
                Iterator<BasicBlock> bbIt = b.getPredecessor_().iterator();
                while (bbIt.hasNext()) {
                    mb.addPred(bMap.get(bbIt.next()));
                }
                //TODO
                //翻译br指令的时候再指定后继基本块。有些情况下某个后继基本块必须要放在本基本块的下一个，跳转指令
//                bbIt=b.getSuccessor_().iterator();
//                while(bbIt.hasNext()){
//                    mb.addSucc(bMap.get(bbIt.next()));
//                }

            }

            


            HandlePhi handlePhi = () -> {
                for (INode<BasicBlock, Function> bbNode : bList) {
                    BasicBlock bb = bbNode.getVal();
                    int predNum = bb.getPredecessor_().size();
                    if (predNum <= 1) {
                        continue;
                    }
                    MachineBlock mbb = bMap.get(bb);
                    if (mbb.getPred() != null) {
                        for (MachineBlock predM : mbb.getPred()) {
                            //prd,succ
                            HashMap<MachineBlock, ArrayList<MachineCode>> map = new HashMap<>();
                            Pair<MachineBlock, MachineBlock> p = new Pair(predM, mbb);
                            //create waiting
                            waiting.put(p, new ArrayList<>());
                        }
                    }
                    IList<Instruction, BasicBlock> irList = bb.getList();
                    //构造phiTarget到phiSet的映射
                    logger.info(bb.getName() + "Map phi target to phi set");
                    for (INode<Instruction, BasicBlock> irNode : irList) {
                        Instruction ir = irNode.getVal();
                        if (ir.tag == Instruction.TAG_.Phi) {
                            String comment = "phi target:";
                            //如果phi指令的参数中有字面量，那也不用调用genImm，因为字面量肯定最后放在前驱块的copy中，所以isInsert设置为false表明这一点
                            MachineOperand phiTarget = analyzeValue(ir, mbb, false);
                            comment += phiTarget.getName() + " params:";
                            assert (phiTarget instanceof VirtualReg);
                            HashSet<MachineOperand> phiSet = new HashSet<>();
                            phiSet.add(phiTarget);
                            for (Value vv : (((Phi) ir).getIncomingVals())) {
                                MachineOperand phiArg = analyzeValue(vv, mbb, false);
//                                if (phiArg.getState() == MachineOperand.state.imm) {
//                                    continue;
//                                }
                                phiSet.add(phiArg);
                                comment += phiArg.getName() + " ";
                            }
                            phiRows.put((VirtualReg) phiTarget, new ArrayList<>());
                            MCComment m = new MCComment(comment, bMap.get(bb));
                        } else {
                            break;
                        }
                    }
                    //大风车吱呀吱哟哟地转 见SSA Elimination after Register Allocation
                    //key是每个前驱块，value的map为phiTarget->phiParam
//                    HashMap<MachineBlock,HashMap<VirtualReg,VirtualReg>> phiGraph=new HashMap<>();
                    //遍历该块的所有pred块
                    logger.info("build phi graph");
                    for (int i = 0; i < predNum; i++) {
                        IList<Instruction, BasicBlock> irrList = bb.getList();
                        HashMap<MachineOperand, MachineOperand> edges = new HashMap<>();
                        for (INode<Instruction, BasicBlock> irrNode : irrList) {
                            Instruction ir = irrNode.getVal();
                            if (ir.tag == Instruction.TAG_.Phi) {
                                MachineOperand phiTarget = analyzeValue(ir, mbb, false);
                                assert (phiTarget instanceof VirtualReg);
                                MachineOperand phiParam = analyzeValue(((Phi) ir).getIncomingVals().get(i), mbb, false);
                                edges.put(phiTarget, phiParam);
                            } else {
                                break;
                            }
                        }
                        ArrayList<ArrayList<MachineOperand>> circles = calcCircle(edges, i);
                        if (!circles.isEmpty()) {
                            Iterator<ArrayList<MachineOperand>> it1 = circles.iterator();
                            while (it1.hasNext()) {
                                ArrayList<MachineOperand> circle = it1.next();
                                Iterator<MachineOperand> it2 = circle.iterator();
                                assert (!circle.isEmpty());
                                VirtualReg temp = new VirtualReg();
                                mf.addVirtualReg(temp);
                                MachineCode mc = new MCMove();
                                ((MCMove) mc).setDst(temp);
                                while (it2.hasNext()) {
                                    MachineOperand vr = it2.next();
                                    ((MCMove) mc).setRhs(vr);
                                    waiting.get(new Pair<>(bMap.get(bb.getPredecessor_().get(i)), mbb)).add(mc);
                                    mc = new MCMove();
                                    ((MCMove) mc).setDst(vr);
                                }
                                ((MCMove) mc).setRhs(temp);
                                waiting.get(new Pair<>(bMap.get(bb.getPredecessor_().get(i)), mbb)).add(mc);
                            }
                        }
                        Iterator<INode<Instruction, BasicBlock>> irItt = irList.iterator();
                        //对于没有环的正常插入copy：phiParam->phiTarget
                        while (irItt.hasNext()) {
                            Instruction ir = irItt.next().getVal();
                            if (ir.tag == Instruction.TAG_.Phi) {
                                MachineOperand phiTarget = analyzeValue(ir, mbb, false);
                                assert (phiTarget instanceof VirtualReg);
                                assert (phiRows.containsKey(phiTarget));
                                if (phiRows.get(phiTarget).get(i)) {
                                    MachineOperand phiParam = analyzeValue(((Phi) ir).getIncomingVals().get(i), mbb, false);
                                    MachineCode mv = new MCMove();
                                    ((MCMove) mv).setRhs(phiParam);
                                    ((MCMove) mv).setDst(phiTarget);
                                    waiting.get(new Pair<>(bMap.get(bb.getPredecessor_().get(i)), mbb)).add(mv);
                                }
                            } else {
                                break;
                            }
                        }

                    }
                }
            };
            //处理phi指令
            logger.info("HandlePhi begin");
            handlePhi.handlephi();


            //处理其余指令
            for (bIt = bList.iterator(); bIt.hasNext(); ) {
                BasicBlock bb = bIt.next().getVal();
                MachineBlock mb = bMap.get(bb);
                MCComment bc=new MCComment("bb:"+bb.getName(),mb);
                for (Iterator<INode<Instruction, BasicBlock>> iIt = bb.getList().iterator(); iIt.hasNext(); ) {
                    Instruction ir = iIt.next().getVal();
                    MCComment co = new MCComment(ir.toString(), mb);
                    if (ir.tag == Instruction.TAG_.Phi) {
                        continue;
                    } else if (ir instanceof BinaryInst && ((BinaryInst) ir).isDiv()) {
                        MachineOperand lhs = analyzeNoImm(ir.getOperands().get(0), mb);
                        MachineOperand rhs;
                        boolean rhsIsConst = ir.getOperands().get(1) instanceof Constants.ConstantInt;
                        //优化除常量
                        if (rhsIsConst && ((Constants.ConstantInt) ir.getOperands().get(1)).getVal() > 0) {
                            int imm = ((Constants.ConstantInt) ir.getOperands().get(1)).getVal();
                            //除以2的幂 TODO
//                            if ((imm & (imm - 1)) == 0) {
//                                assert (imm != 0);
//                                MachineOperand dst = analyzeValue(ir, mb, true);
//                                MCMove mv = new MCMove(mb);
//                                mv.setDst(dst);
//                                mv.setRhs(lhs);
//                                mv.setShift(ArmAddition.ShiftType.Lsr, calcCTZ(imm));
//                            } else {
                                long nc = ((long) 1 << 31) - (((long) 1 << 31) % imm) - 1;
                                long p = 32;
                                while (((long) 1 << p) <= nc * (imm - ((long) 1 << p) % imm)) {
                                    p++;
                                }
                                long m = ((((long) 1 << p) + (long) imm - ((long) 1 << p) % imm) / (long) imm);
                                int n = (int) ((m << 32) >>> 32);
                                int shift = (int) (p - 32);
                                MCMove mc0 = new MCMove(mb);
                                VirtualReg v = new VirtualReg();
                                mf.addVirtualReg(v);
                                mc0.setDst(v);
                                mc0.setRhs(new MachineOperand(n));
                                VirtualReg v1 = new VirtualReg();
                                mf.addVirtualReg(v1);
                                //2147483648L=0x80000000
                                if (m >= 2147483648L) {
                                    MCFma mc2 = new MCFma(mb);
                                    mc2.setAdd(true);
                                    mc2.setSign(true);
                                    mc2.setDst(v1);
                                    mc2.setLhs(lhs);
                                    mc2.setRhs(v);
                                    mc2.setAcc(lhs);
                                } else {
                                    MCLongMul mc1 = new MCLongMul(mb);
                                    mc1.setDst(v1);
                                    mc1.setRhs(v);
                                    mc1.setLhs(lhs);
                                }
                                MCMove mc3 = new MCMove(mb);
                                VirtualReg v3 = new VirtualReg();
                                mf.addVirtualReg(v3);
                                mc3.setDst(v3);
                                mc3.setRhs(v1);
                                mc3.setShift(ArmAddition.ShiftType.Asr, shift);
                                MachineOperand dst = analyzeValue(ir, mb, true);
                                MCBinary mc4 = new MCBinary(MachineCode.TAG.Add, mb);
                                mc4.setDst(dst);
                                mc4.setLhs(mc3.getDst());
                                mc4.setRhs(lhs);
                                mc4.setShift(ArmAddition.ShiftType.Lsr, 31);
//                            }
                        } else {
                            rhs = analyzeNoImm(ir.getOperands().get(1), mb);
                            MachineOperand dst = analyzeValue(ir, mb, true);
                            MCBinary binary = new MCBinary(MachineCode.TAG.Div, mb);
                            binary.setLhs(lhs);
                            binary.setRhs(rhs);
                            binary.setDst(dst);
                        }
                    } else if (ir instanceof BinaryInst && ((BinaryInst) ir).isMul()) {
                        boolean rhsIsConst = ir.getOperands().get(1) instanceof Constants.ConstantInt;
                        boolean lhsIsConst = ir.getOperands().get(0) instanceof Constants.ConstantInt;
                        MachineOperand lhs;
                        MachineOperand rhs;
                        if (lhsIsConst || rhsIsConst) {
                            int imm;
                            if (lhsIsConst) {
                                lhs = analyzeNoImm(ir.getOperands().get(1), mb);
                                imm = ((Constants.ConstantInt) ir.getOperands().get(0)).getVal();
                            } else {
                                lhs = analyzeNoImm(ir.getOperands().get(0), mb);
                                imm = ((Constants.ConstantInt) ir.getOperands().get(1)).getVal();
                            }
                            int log = calcCTZ(imm);
                            MachineOperand dst = analyzeValue(ir, mb, true);
                            //乘以2的幂
                            if ((imm & (imm - 1)) == 0) {
                                MCMove mc = new MCMove(mb);
                                mc.setDst(dst);
                                if (imm == 0) {
                                    mc.setRhs(new MachineOperand(0));
                                    continue;
                                }
                                mc.setRhs(lhs);
                                if (log > 0) {
                                    mc.setShift(ArmAddition.ShiftType.Lsl, log);
                                }
                                continue;
                            } else if (((imm - 2) & (imm - 1)) == 0) {//乘以（2的幂+1）
                                MCBinary add = new MCBinary(MachineCode.TAG.Add, mb);
                                add.setDst(dst);
                                add.setLhs(lhs);
                                add.setRhs(lhs);
                                add.setShift(ArmAddition.ShiftType.Lsl, log);
                                continue;
                            }
                            if (lhsIsConst) {
                                rhs = analyzeNoImm(ir.getOperands().get(0), mb);
                            } else {
                                rhs = analyzeNoImm(ir.getOperands().get(1), mb);
                            }
                        } else {
                            lhs = analyzeNoImm(ir.getOperands().get(0), mb);
                            rhs = analyzeNoImm(ir.getOperands().get(1), mb);
                        }
                        MCBinary mul = new MCBinary(MachineCode.TAG.Mul, mb);
                        mul.setLhs(lhs);
                        mul.setRhs(rhs);
                        mul.setDst(analyzeValue(ir,mb,true));
                    } else if (ir instanceof BinaryInst && (((BinaryInst) ir).isSub() || ((BinaryInst) ir).isAdd())) {
                        boolean rhsIsConst = ir.getOperands().get(1) instanceof Constants.ConstantInt;
                        boolean lhsIsConst = ir.getOperands().get(0) instanceof Constants.ConstantInt;
                        MachineOperand lhs;
                        MachineOperand rhs;
                        BinaryInst b = (BinaryInst) ir;
                        if (b.isAdd() && lhsIsConst && !rhsIsConst) {
                            lhs = analyzeValue(ir.getOperands().get(1), mb, true);
                            rhs = analyzeValue(ir.getOperands().get(0), mb, true);
                        } else {
                            lhs = analyzeNoImm(ir.getOperands().get(0), mb);
                            rhs = analyzeValue(ir.getOperands().get(1), mb, true);
                        }
                        if (rhs.getState() == MachineOperand.state.imm) {
                            int imm = rhs.getImm();
                            if (!canEncodeImm(imm) && canEncodeImm(-imm)) {
                                imm = -imm;
                                MachineOperand immm = genImm(imm, mb);
                                MCBinary in;
                                in = ((BinaryInst) ir).isAdd() ? new MCBinary(MachineCode.TAG.Sub, mb) : new MCBinary(MachineCode.TAG.Add, mb);
                                MachineOperand dst = analyzeValue(ir, mb, true);
                                in.setDst(dst);
                                in.setRhs(immm);
                                in.setLhs(lhs);
                                continue;
                            }
                        }
                        MCBinary binary;
                        binary = ((BinaryInst) ir).isAdd() ? new MCBinary(MachineCode.TAG.Add, mb) : new MCBinary(MachineCode.TAG.Sub, mb);
                        MachineOperand dst = analyzeValue(ir, mb, true);
                        binary.setRhs(rhs);
                        binary.setLhs(lhs);
                        binary.setDst(dst);
                    } else if (ir instanceof BinaryInst && ((BinaryInst) ir).isCond()) {
                        continue;
                    }  else if (ir.tag == Instruction.TAG_.Br) {
                        if (ir.getNumOP() == 3) {
                            CondType cond =dealCond((BinaryInst) (ir.getOperands().get(0)),mb,false);
                            MachineCode br = new MCBranch(mb);
                            ((MCBranch) br).setCond(cond);
                            int trueT=1;
                            int falseT=2;
                            if(cond!=getCond((BinaryInst) (ir.getOperands().get(0)))){
                                trueT=2;
                                falseT=1;
                            }
                            //set trueblock to branch target
                            ((MCBranch) br).setTarget(bMap.get(ir.getOperands().get(trueT)));
                            mb.setFalseSucc(bMap.get(ir.getOperands().get(falseT)));
                            mb.setTrueSucc(bMap.get(ir.getOperands().get(trueT)));
                        } else {
                            assert (ir.getNumOP() == 1);
                            //如果只有一个后继块，那么此跳转指令就是废的
//                            if (bb.getPredecessor_().size() == 1) {
//                                mb.setFalseSucc(bMap.get(ir.getOperands().get(0)));
//                                continue;
//                            }
                            MachineCode j = new MCJump(mb);
                            ((MCJump) j).setTarget(bMap.get(ir.getOperands().get(0)));
                            mb.setTrueSucc(bMap.get(ir.getOperands().get(0)));

                        }


                    } else if (ir.tag == Instruction.TAG_.Call) {
                        MachineCode call = new MCCall();
                        //获取调用函数的参数数量
                        int argNum = ir.getOperands().size() - 1;
                        for (int i = 0; i < argNum; i++) {
                            if (i < 4) {
                                MachineOperand rhs = analyzeValue(ir.getOperands().get(i + 1), mb, true);
                                MachineCode mv = new MCMove(mb);
                                ((MCMove) mv).setRhs(rhs);
                                ((MCMove) mv).setDst(mf.getPhyReg(i));
                                //防止寄存器分配消除掉这些move
                                call.addUse(((MCMove) mv).getDst());
                            } else {
                                VirtualReg vr = (VirtualReg) analyzeNoImm(ir.getOperands().get(i + 1), mb);
                                MachineOperand imm = genImm((-(argNum - i) * 4), mb);
                                MachineCode st = new MCStore(mb);
                                ((MCStore) st).setData(vr);
                                ((MCStore) st).setAddr(mf.getPhyReg("sp"));
                                ((MCStore) st).setOffset(imm);
                            }
                        }
                        if (argNum > 4) {
                            MachineCode sub = new MCBinary(MachineCode.TAG.Sub, mb);
                            ((MCBinary) sub).setDst(mf.getPhyReg("sp"));
                            ((MCBinary) sub).setLhs(mf.getPhyReg("sp"));
                            ((MCBinary) sub).setRhs(new MachineOperand(4 * (argNum - 4)));
                        }
                        assert (ir.getOperands().get(0) instanceof Function);
                        ((MCCall) call).setFunc(fMap.get((Function) ir.getOperands().get(0)));
                        call.setMb(mb);
                        if (argNum > 4) {
                            MachineCode add = new MCBinary(MachineCode.TAG.Add, mb);
                            ((MCBinary) add).setDst(mf.getPhyReg("sp"));
                            ((MCBinary) add).setLhs(mf.getPhyReg("sp"));
                            ((MCBinary) add).setRhs(new MachineOperand(4 * (argNum - 4)));
                        }
                        for (int i = 0; i < 4; i++) {
                            call.addDef(mf.getPhyReg(i));
                        }
                        //TODO:如果调用的函数有返回值，那么会def里应有r0，但是r1-r3不应该出现在def里，后面考虑删去
                        if (!((Function) (ir.getOperands().get(0))).getType().getRetType().isVoidTy()) {
                            MCMove mv = new MCMove(mb);
                            mv.setDst(analyzeValue(ir, mb, true));
                            mv.setRhs(mf.getPhyReg("r0"));
                        }
                        call.addDef(mf.getPhyReg("lr"));
                        //TODO:可能没用，后面考虑把ip寄存器当通用寄存器分配
                        call.addDef(mf.getPhyReg("ip"));
                    } else if (ir.tag == Instruction.TAG_.Ret) {
                        //如果有返回值
                        if (((TerminatorInst.RetInst) ir).getNumOP() != 0) {
                            MachineOperand res = analyzeValue(ir.getOperands().get(0), mb, true);
                            MCMove mv = new MCMove(mb);
                            mv.setDst(mf.getPhyReg("r0"));
                            mv.setRhs(res);
                            MCReturn re = new MCReturn(mb);
                        } else {
                            MCReturn re = new MCReturn(mb);
                        }
                    } else if (ir.tag == Instruction.TAG_.Alloca) {
                        //TODO 如何获得到底是哪个指针
                        //如果alloca出来的指针是个二重指针
                        if (((PointerType) (ir).getType()).getContained().isPointerTy()) {
//                            analyzeValue(ir, mb, true);
                            continue;
                        }
                        assert (ir.getType() instanceof PointerType);
                        Type ttype = ((PointerType) ir.getType()).getContained();
//                        if(ttype instanceof PointerType){
//                            var type =((PointerType) ((PointerType)ir.getType()).getContained()).getContained();
//                            if(type.isIntegerTy()){
//
//                            }
//                            if (type.isArrayTy()){
//
//                            }
//                        }else
                        MachineOperand offset;
                        if (ttype instanceof IntegerType || ttype instanceof PointerType) {
                            offset = genImm(4, mb);
                        } else {
                            assert (ttype instanceof ArrayType);
                            int size = 4;
                            Iterator<Integer> dimList = ((ArrayType) ((PointerType) ir.getType()).getContained()).getDims().iterator();
                            while (dimList.hasNext()) {
                                size *= dimList.next();
                            }
                            offset = genImm(mf.getStackSize(), mb);
                            mf.addStackSize(size);
                        }
                        assert (((PointerType) ir.getType()).getContained() instanceof ArrayType);
                        MachineOperand dst = analyzeValue(ir, mb, true);
                        MCBinary add = new MCBinary(MachineCode.TAG.Add, mb);
                        add.setDst(dst);
                        add.setLhs(mf.getPhyReg("sp"));
                        add.setRhs(offset);

//                        if (ir.getType() instanceof PointerType) {
//
//                        } else {
//                            //alloca整数已被mem2reg优化
//                        }
                    } else if (ir.tag == Instruction.TAG_.Load) {
                        Value ar = ir.getOperands().get(0);
                        //如果load的地址是二重指针
                        if (((PointerType) (ar).getType()).getContained().isPointerTy()) {
                            loadToAlloca.put((MemInst.LoadInst) ir, (MemInst.AllocaInst) ar);
                            continue;
                        }
                        MachineOperand dst = analyzeValue(ir, mb, true);
                        MachineOperand addr = analyzeValue(ir.getOperands().get(0), mb, true);
                        MachineOperand offset = new MachineOperand(0);
                        MCLoad load = new MCLoad(mb);
                        load.setDst(dst);
                        load.setOffset(offset);
                        load.setAddr(addr);
                    } else if (ir.tag == Instruction.TAG_.Store) {
                        Value ar = ir.getOperands().get(1);
                        //如果store的指针是个二重指针
                        if (((PointerType) (ar).getType()).getContained().isPointerTy()) {
                            allocaToStore.put((MemInst.AllocaInst) ar, ir.getOperands().get(0));
                            analyzeValue(ir.getOperands().get(0), mb, true);
                            continue;
                        }
                        MachineOperand arr = analyzeValue(ir.getOperands().get(1), mb, true);
                        MachineOperand data = analyzeNoImm(ir.getOperands().get(0), mb);
                        MachineOperand offset = new MachineOperand(0);
                        MCStore store = new MCStore(mb);
                        store.setData(data);
                        store.setOffset(offset);
                        store.setAddr(arr);

                    } else if (ir.tag == Instruction.TAG_.GEP) {
                        //最后一个gep应该被优化合并到load/store里
//                        assert (!(ir.getType() instanceof IntegerType));
                        //基址为上一个gep
                        assert (ir.getOperands().get(0).getType() instanceof PointerType);
                        PointerType pt = (PointerType) ir.getOperands().get(0).getType();
                        //获取偏移个数
                        int offsetNum;
                        ArrayList<Integer> dimInfo;
                        if (pt.getContained() instanceof IntegerType) {
                            offsetNum = 1;
                            dimInfo = new ArrayList<>();
                        } else {
                            //gep中的基址不能是二重指针
                            assert (pt.getContained() instanceof ArrayType);
                            offsetNum = ir.getOperands().size() - 1;
                            dimInfo = (((ArrayType) (pt).getContained()).getDims());
                        }
                        MachineOperand arr = analyzeValue(ir.getOperands().get(0), mb, true);
                        int lastoff = 0;
                        for (int i = 1; i <= offsetNum; i++) {
                            //数组基址不能是常量
                            assert (arr.getState() != MachineOperand.state.imm);
                            //获取偏移
                            MachineOperand off = analyzeValue(ir.getOperands().get(i), mb, true);
                            boolean isOffConst = off.getState() == MachineOperand.state.imm;
                            //获取当前维度长度，即偏移的单位
                            int mult = 4;
                            if (pt.getContained() instanceof ArrayType) {
                                for (int j = i - 1; j < dimInfo.size(); j++) {
                                    mult *= dimInfo.get(j);
                                }
                            }
//                            if (mult == 0 || (isOffConst && off.getImm() == 0)) {
//                                MachineOperand dst = analyzeValue(ir);
//                                MCMove mv = new MCMove(mb);
//                                mv.setDst(dst);
//                                mv.setRhs(arr);
//                                arr=dst;
//                            } else
                            if (isOffConst) {
                                int totalOff = mult * off.getImm();
                                //如果是gep中最后一个偏移
                                if (i == offsetNum) {
                                    MachineOperand dst = analyzeValue(ir, mb, true);
                                    totalOff += lastoff;
                                    if (totalOff == 0) {
                                        MCMove mv = new MCMove(mb);
                                        mv.setDst(dst);
                                        mv.setRhs(arr);
                                    } else {
                                        MachineOperand imm = genImm(totalOff, mb);
                                        MCBinary add = new MCBinary(MachineCode.TAG.Add, mb);
                                        add.setDst(dst);
                                        add.setLhs(arr);
                                        add.setRhs(imm);
                                    }
                                    arr = dst;
                                } else {
                                    lastoff += totalOff;
                                }
                            } else if ((mult & (mult - 1)) == 0) {
                                MachineOperand dst = analyzeValue(ir, mb, true);
                                if (lastoff != 0) {
                                    MachineOperand lhs = genImm(lastoff, mb);
                                    MCBinary ladd = new MCBinary(MachineCode.TAG.Add, mb);
                                    ladd.setDst(arr);
                                    ladd.setRhs(arr);
                                    ladd.setLhs(lhs);
                                    lastoff = 0;
                                }
                                ArmAddition.Shift s = ArmAddition.getAddition().getNewShiftInstance();
                                s.setType(ArmAddition.ShiftType.Lsl, calcCTZ(mult));
                                MachineOperand rhs = dealShiftImm(s, off, mb);
                                MCBinary add = new MCBinary(MachineCode.TAG.Add, mb);
                                add.setDst(dst);
                                add.setLhs(arr);
                                add.setRhs(rhs);
                                add.setShift(s.getType(), s.getImm());
                                arr = dst;
                            } else {
                                if (lastoff != 0) {
                                    MachineOperand rhs = genImm(lastoff, mb);
                                    MCBinary ladd = new MCBinary(MachineCode.TAG.Add, mb);
                                    ladd.setDst(arr);
                                    ladd.setLhs(arr);
                                    ladd.setRhs(rhs);
                                    lastoff = 0;
                                }
                                VirtualReg v = new VirtualReg();
                                mf.addVirtualReg(v);
                                MCMove mv = new MCMove(mb);
                                mv.setDst(v);
                                mv.setRhs(new MachineOperand(mult));
                                VirtualReg v1 = (VirtualReg) analyzeValue(ir, mb, true);
                                mf.addVirtualReg(v1);
                                MCFma fma = new MCFma(mb);
                                fma.setRhs(v);
                                fma.setLhs(off);
                                fma.setAcc(arr);
                                fma.setDst(v1);
                                fma.setAdd(true);
                                fma.setSign(false);
                                arr = v1;
                            }
                        }

                    }else if(ir instanceof MemInst.ZextInst){
                        MachineOperand dst=analyzeValue(ir,mb,true);
                        MachineOperand rhs=analyzeValue(ir.getOperands().get(0),mb,true);
                        if(!(ir.getOperands().get(0) instanceof Constants.ConstantInt)){
                            dealCond((BinaryInst) (ir.getOperands().get(0)),mb,true);
                        }
                        MCMove mv=new MCMove(mb);
                        mv.setRhs(rhs);
                        mv.setDst(dst);
                    }
                }
            }


            DFSSerialize s = () -> {
                HashMap<MachineBlock, Boolean> isVisit = new HashMap<>();
                dfsSerial(bMap.get(f.getList_().getEntry().getVal()), mf, isVisit);
            };

            s.dfsSerialize();

        }
    }

    //计算最低位1的位数，如果输入0，返回0
    private int calcCTZ(int n) {
        int res = 0;
        n = n >>> 1;
        while (n != 0) {
            n = n >>> 1;
            res++;
        }
        return res;
    }

    private CondType getOppoCond(CondType t) {
        if (t == CondType.Lt) {
            return CondType.Ge;
        } else if (t == CondType.Le) {
            return CondType.Gt;
        } else if (t == CondType.Ge) {
            return CondType.Lt;
        } else if (t == CondType.Gt) {
            return CondType.Le;
        } else if (t == CondType.Eq) {
            return CondType.Ne;
        } else if (t == CondType.Ne) {
            return CondType.Eq;
        } else {
            assert (false);
            return CondType.Eq;
        }
    }

    private CondType getCond(BinaryInst bI) {
        if (bI.isLt()) {
            return CondType.Lt;
        } else if (bI.isLe()) {
            return CondType.Le;
        } else if (bI.isGe()) {
            return CondType.Ge;
        } else if (bI.isGt()) {
            return CondType.Gt;
        } else if (bI.isEq()) {
            return CondType.Eq;
        } else if (bI.isNe()) {
            return CondType.Ne;
        } else {
            assert (false);
            return CondType.Ge;
        }
    }

    //计算来自某一前驱块的一堆phiTarget中哪些在环中，共有几个环。
    private ArrayList<ArrayList<MachineOperand>> calcCircle(HashMap<MachineOperand, MachineOperand> graph, int i) {
        ArrayList<ArrayList<MachineOperand>> result = new ArrayList<>();
        while (!graph.isEmpty()) {
            //从剩余图中获得一个节点
            Iterator<Map.Entry<MachineOperand, MachineOperand>> ite = graph.entrySet().iterator();
            MachineOperand now = ite.next().getKey();
            Stack<MachineOperand> stack = new Stack<>();
            //深度优先搜索
            while (true) {
                //如果一个节点没有出度，退出循环
                if (!graph.containsKey(now)) {
                    break;
                } else if (stack.contains(now)) {
                    break;
                } else {
                    stack.push(now);
                    now = graph.get(now);
                }
            }
            //如果以该点出发没有环路，那么从graph中删去把栈内所有点
            if (!graph.containsKey(now)) {
                while (!stack.isEmpty()) {
                    MachineOperand r = stack.pop();
                    assert (graph.containsKey(r));
                    assert (phiRows.get(r).size() == i);
                    phiRows.get(r).add(true);
                    graph.remove(r);
                }
            } else {
                ArrayList<MachineOperand> circle = new ArrayList<>();
                assert (stack.contains(now));
                while (stack.contains(now)) {
                    MachineOperand r = stack.pop();
                    circle.add(r);
                    assert (graph.containsKey(r));
                    assert (phiRows.get(r).size() == i);
                    phiRows.get(r).add(false);
                    graph.remove(r);
                }
                while (!stack.isEmpty()) {
                    MachineOperand r = stack.pop();
                    assert (graph.containsKey(r));
                    assert (phiRows.get(r).size() == i);
                    phiRows.get(r).add(true);
                    graph.remove(r);
                }
                result.add(circle);
            }
        }
        return result;
    }

    private MachineOperand dealShiftImm(ArmAddition.Shift s, MachineOperand mo, MachineBlock mb) {
        int ss = s.getImm();
        ArmAddition.ShiftType t = s.getType();
        if (mo.getState() == MachineOperand.state.imm && t != ArmAddition.ShiftType.None) {
            int imm = mo.getImm();
            if (t == ArmAddition.ShiftType.Asr) {
                imm = imm >> ss;
            } else if (t == ArmAddition.ShiftType.Lsl) {
                imm = imm << ss;
            } else if (t == ArmAddition.ShiftType.Lsr) {
                imm = imm >>> ss;
            } else if (t == ArmAddition.ShiftType.Ror) {
                imm = (imm >>> ss) | (imm << (32 - ss));
            } else {
                //TODO:Rrx
            }
            s.setType(ArmAddition.ShiftType.None, 0);
            return genImm(imm, mb);
        } else {
            return mo;
        }
    }

    //函数传数组指针，会出现低效问题，声明两个map解决此问题
    //对于a[],会有alloca b store a b load d b
    //其中a,d是一个数组首地址，b是一个二重指针，但是d和a其实是一个东西，把对d的使用转换成对a的使用
    HashMap<MemInst.LoadInst, MemInst.AllocaInst> loadToAlloca = new HashMap<>();
    HashMap<MemInst.AllocaInst, Value> allocaToStore = new HashMap<>();
    HashMap<GlobalVariable, MCLoad> globalMap = new HashMap<>();
    MachineFunction mf = null;
    Function f = null;
    HashMap<BasicBlock, MachineBlock> bMap = new HashMap<>();


    private MachineOperand analyzeValue(Value v, MachineBlock mb, boolean isInsert) {
        if (v instanceof Function.Arg && f.getArgList().contains(v)) {
            VirtualReg vr;
            if (irMap.get(v) == null) {
                vr = new VirtualReg(v.getName());
                irMap.put(v, vr);
                mf.addVirtualReg(vr);
                for (int i = 0; i < f.getNumArgs(); i++) {
                    if (f.getArgList().get(i) == v) {
                        if (i < 4) {
                            MachineCode mc = new MCMove(bMap.get(f.getList_().getEntry().getVal()), 0);
                            ((MCMove) mc).setDst(vr);
                            ((MCMove) mc).setRhs(mf.getPhyReg(i));
                        } else {
                            VirtualReg vR = new VirtualReg();
                            mf.addVirtualReg(vR);
                            MachineCode mcLD = new MCLoad(bMap.get(f.getList_().getEntry().getVal()), 0);
                            ((MCLoad) mcLD).setAddr(mf.getPhyReg("sp"));
                            ((MCLoad) mcLD).setOffset(vR);
                            ((MCLoad) mcLD).setDst(vr);
                            MCMove mv = new MCMove(bMap.get(f.getList_().getEntry().getVal()), 0);
                            mv.setRhs(new MachineOperand((i - 4) * 4));
                            mv.setDst(vR);
                            mf.getArgMoves().add(mv);
                        }
                        break;
                    }
                }
                return vr;
            } else {

                return irMap.get(v);
            }
        } else if (myModule.__globalVariables.contains(v)) {
            assert irMap.containsKey(v);
            if (globalMap.containsKey(v)) {
                return globalMap.get(v).getDst();
            } else {
                MCLoad l = new MCLoad(mb);
                l.setAddr(irMap.get(v));
                VirtualReg r = new VirtualReg();
                mf.addVirtualReg(r);
                l.setDst(r);
                l.setOffset(new MachineOperand(0));
                return l.getDst();
            }

        } else if (v instanceof Constants.ConstantInt) {
            if (isInsert)
                return genImm(((Constants.ConstantInt) v).getVal(), mb);
            else
                return new MachineOperand(((Constants.ConstantInt) v).getVal());
        } else if (v instanceof MemInst.LoadInst && loadToAlloca.containsKey(v)) {
            assert (allocaToStore.containsKey(loadToAlloca.get(v)));
            assert (irMap.containsKey(allocaToStore.get(loadToAlloca.get(v))));
            return irMap.get(allocaToStore.get(loadToAlloca.get(v)));
        } else {
            if (!irMap.containsKey(v)) {
                VirtualReg vr = new VirtualReg(v.getName());
                mf.addVirtualReg(vr);
                irMap.put(v, vr);
                return vr;
            } else {
                return irMap.get(v);
            }
        }
    }
    
    private MachineOperand analyzeNoImm(Value v,MachineBlock mb){
        if (v instanceof Constants.ConstantInt) {
            VirtualReg vr = new VirtualReg();
            mf.addVirtualReg(vr);
            mb.getMF().addVirtualReg(vr);
            MachineCode mv = new MCMove(mb);
            ((MCMove) mv).setDst(vr);
            ((MCMove) mv).setRhs(new MachineOperand(((Constants.ConstantInt) v).getVal()));
            return vr;
        } else {
            return analyzeValue(v, mb, false);
        }
    }

    //处理Cond，addMove为是否生成move指令
    private CondType dealCond(BinaryInst ir, MachineBlock mb,boolean addMove) {
        boolean rhsIsConst = ir.getOperands().get(1) instanceof Constants.ConstantInt;
        boolean lhsIsConst = ir.getOperands().get(0) instanceof Constants.ConstantInt;
        MachineOperand lhs;
        MachineOperand rhs;
        Value l;
        Value r;
        CondType cond = getCond(ir);
        if (lhsIsConst && !rhsIsConst) {
            l=ir.getOperands().get(1);
            r=ir.getOperands().get(0);
            cond = getOppoCond(cond);
        } else {
            l=ir.getOperands().get(0);
            r=ir.getOperands().get(1);
        }
        if(l instanceof BinaryInst && ((BinaryInst)l).isCond()){
            dealCond((BinaryInst) l,mb,true);
        }
        if(r instanceof BinaryInst && ((BinaryInst)r).isCond()){
            dealCond((BinaryInst) r,mb,true);
        }
        lhs = analyzeNoImm(l, mb);
        rhs = analyzeValue(r, mb, true);
        MCCompare compare = new MCCompare(mb);
        compare.setRhs(rhs);
        compare.setLhs(lhs);
        compare.setCond(cond);
        MachineOperand dst=analyzeValue(ir,mb,true);
        if(addMove){
            MCMove pMv=new MCMove(mb);
            pMv.setRhs(new MachineOperand(1));
            pMv.setDst(dst);
            pMv.setCond(cond);
            MCMove nMv=new MCMove(mb);
            nMv.setRhs(new MachineOperand(0));
            nMv.setDst(dst);
            nMv.setCond(getOppoCond(cond));
        }
        return cond;

    }


    //pred->(succ->MCs)
    private HashMap<Pair<MachineBlock, MachineBlock>, ArrayList<MachineCode>> waiting = new HashMap<>();

}
