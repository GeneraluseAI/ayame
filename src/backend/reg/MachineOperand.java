package backend.reg;

public class MachineOperand {
    public enum state{
        virtual,
        phy,
        imm
    }

    //if MO is imm, the value is imme
    int imme;

    state s;
    MachineOperand(state s){
        this.s=s;
    }

    MachineOperand(int imme){
        this.s=state.imm;
        this.imme=imme;
    }
}
