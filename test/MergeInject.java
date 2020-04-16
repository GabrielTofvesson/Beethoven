import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;
import dev.w1zzrd.asm.Merger;

@InjectClass(value = MergeTest.class)
public class MergeInject implements Runnable {

    @Inject
    public String test(){
        System.out.println(Merger.field("s"));
        return "Modified";
    }

    @Override
    @Inject
    public void run() {
        for (int i = 0; i < 5; ++i)
            System.out.println(test());
    }
}
