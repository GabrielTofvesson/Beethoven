import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;

@InjectClass(value = MergeTest.class)
public class MergeInject implements Runnable {

    @Inject
    public int number;

    // Dummy field
    String s;

    @Inject
    MergeInject() {
        s = "Hello";
        number = 10;
    }

    @Inject
    private String test(){
        System.out.println(s);
        System.out.println(number);
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
