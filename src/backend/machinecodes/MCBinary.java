package backend.machinecodes;

import backend.reg.VirtualReg;

/**
 * Add,Sub,Rsb, Mul, Div, Mod, Lt, Le, Ge, Gt, Eq, Ne, And, Or
 */
public class MCBinary extends MachineCode{

    private VirtualReg dst;

    private VirtualReg lhs;

    private VirtualReg rhs;

    private ArmAddition.Shift shift = ArmAddition.getAddition().getShiftInstance();

    public MCBinary(TAG tag, MachineBlock mb){
        super(tag,mb);
    }

}
