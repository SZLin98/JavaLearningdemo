package lsz.controller2;

import lsz.component.AComponent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class testClass2 {

    @Autowired
    AComponent aComponent;

    @RequestMapping(value = "/")
    public String testFunction(){
        return aComponent.test();
    }

}
