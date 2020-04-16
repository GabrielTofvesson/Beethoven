import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;

@InjectClass(value = MergeTest.class)
public class MergeInject implements Runnable {

    // Dummy field
    String s;

    @Inject
    public String test(){
        System.out.println(s);
        return "Modified";
    }

    @Override
    @Inject
    public void run() {
        for (int i = 0; i < 5; ++i) {
            s = s + "!";
            System.out.println(test());
        }
    }
}
