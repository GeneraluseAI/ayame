package backend.machinecodes;

import backend.CodeGenManager;
import backend.reg.MachineOperand;



public class MCMove extends MachineCode{

    public MachineOperand getDst() {
        return dst;
    }

    public void setDst(MachineOperand dst) {
        dealReg(this.dst,dst,false);
        this.dst = dst;
    }

    public MachineOperand getRhs() {
        return rhs;
    }

    public void setRhs(MachineOperand rhs) {
        dealReg(this.rhs,rhs,true);
        this.rhs = rhs;
    }

    private MachineOperand dst;

    private MachineOperand rhs;

    @Override
    public ArmAddition.CondType getCond() {
        return cond;
    }

    @Override
    public String toString(){
        String res="\t";
        if(rhs.getState()== MachineOperand.state.imm&&!CodeGenManager.canEncodeImm(rhs.getImm())){
            int imm=rhs.getImm();
            int immH=imm>>>16;
            int immL=(imm<<16)>>>16;
            res+="movw"+contString(cond)+"\t"+dst.getName()+",\t#"+immL+"\n";
            if(immH!=0) {
                res+="\tmovt"+contString(cond)+"\t"+dst.getName()+",\t#"+immH+"\n";
            }
        }else{
            res+="mov"+contString(cond)+"\t"+dst.getName()+",\t"+rhs.getName()+getShift().toString()+"\n";
        }
        return res;
    }

    public void setCond(ArmAddition.CondType cond) {
        this.cond = cond;
    }

    private ArmAddition.CondType cond = ArmAddition.CondType.Any;

    public MCMove(MachineBlock mb){
        super(TAG.Mv,mb);
    }

    public MCMove(){
        super(TAG.Mv);
    }

    public MCMove(MachineBlock mb,int num){
        super(TAG.Mv,mb,num);
    }
}
