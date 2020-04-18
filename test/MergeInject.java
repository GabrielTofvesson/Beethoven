import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;
import static dev.w1zzrd.asm.InPlaceInjection.*;

@InjectClass(value = MergeTest.class)
public class MergeInject extends MergeTest implements Runnable {

    @Inject
    public int number;

    // Dummy field
    String s;

    @Inject
    MergeInject() {
        s = "Hello";
        number = 10;
    }

    @Inject(REPLACE)
    public String test(){
        System.out.println(s);

        if(s.endsWith("e!!")) {
            System.out.println("Special!");
            return "ASDF";
        }

        System.out.println(number);

        return "Modified";
    }

    @Override
    @Inject
    public void run() {
        for (int i = 0; i < 5; ++i) {
            s = s + "!";
            System.out.println(test()+'\n');
        }
    }

    public String test1(){ return null; }
}
