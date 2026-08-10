package gov.state.missionchat;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping({"/missionchat", "/missionchat/"})
    public String missionChat() {
        return "forward:/missionchat.html";
    }

    @GetMapping({"/cableschat", "/cableschat/"})
    public String cablesChat() {
        return "forward:/cableschat.html";
    }

    @GetMapping({"/documentchat", "/documentchat/"})
    public String documentsChat() {
        return "forward:/documentchat.html";
    }

    @GetMapping({"/aisandbox", "/aisandbox/"})
    public String aiSandbox() {
        return "forward:/aisandbox.html";
    }
}
