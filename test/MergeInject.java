import dev.w1zzrd.asm.Directives;
import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;

import java.util.concurrent.ThreadLocalRandom;

import static dev.w1zzrd.asm.InPlaceInjection.*;

@InjectClass(MergeTest.class)
public class MergeInject extends MergeTest implements Runnable {

    @Inject
    public int number;

    // Dummy field
    @Inject
    private String s;


    @Inject
    MergeInject() {
        Directives.callSuper();
        s = "Hello";
        number = 10;
    }


    @Inject(value = BEFORE, target = "stackTest()I")
    public int beforeStackTest() {
        System.out.println("This is before stack test");
        if (ThreadLocalRandom.current().nextBoolean()) {
            System.out.println("Shortcut");
            return 69420;
        }

        this.number = ThreadLocalRandom.current().nextInt();

        System.out.println(number);

        Directives.callOriginal();
        return 0;
    }


    @Inject(value = AFTER, acceptOriginalReturn = true)
    public int stackTest(int arg) {
        Runnable r = () -> {
          System.out.println(arg / 15);
          System.out.println("Heyo");
        };
        r.run();
        return 69;
    }


    @Inject(value = AFTER, acceptOriginalReturn = true)
    public String test(String retVal){

        System.out.println(retVal + "Cringe");

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
}
