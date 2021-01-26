import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;

import static dev.w1zzrd.asm.InPlaceInjection.*;

@InjectClass(value = MergeTest.class)
public class MergeInject extends MergeTest implements Runnable {

    @Inject
    public int number;

    // Dummy field
    String s;

    /*
    @Inject
    MergeInject() {
        s = "Hello";
        number = 10;
    }

     */





    @Inject(value = AFTER, target = "stackTest()I", acceptOriginalReturn = true)
    public int stackTest(int arg) {
        Runnable r = () -> {
          System.out.println(arg / 15);
          System.out.println("Heyo");
        };
        r.run();
        return 69;
    }


    @Inject(value = AFTER, target = "test()Ljava/lang/String;", acceptOriginalReturn = true)
    public String test(String retVal){

        System.out.println("Got retval: "+retVal);

        if (retVal.endsWith("e!!Test")) {
            System.out.println("Special case!");
            return "Not Modified?";
        }

        retVal = "ASDF";

        System.out.println("Eyyyy");

        String a = "retVal";

        System.out.println(a);
        a = "Test";
        System.out.println(a);

        System.out.println(s);



        if(s.endsWith("e!!")) {
            System.out.println("Special!");
            return "ASDF";
        }



        System.out.println(s);


        return "Modified";
    }

    /*
    @Inject(value = AFTER, target = "test()Ljava/lang/String;", acceptOriginalReturn = true)
    public String test_inject$1(String retVal) {
        System.out.println("Another injection: "+retVal);
        return retVal;
    }
     */











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
