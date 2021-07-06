package backend.machinecodes;

import backend.reg.VirtualReg;

public class MCLoad extends MachineCode{

    private VirtualReg addr;

    private VirtualReg offset;

    private VirtualReg data;

    ArmAddition.Shift shift=ArmAddition.getAddition().getShiftInstance();

    MCLoad(MachineBlock mb){
        super(MachineCode.TAG.Branch,mb);
    }
}
