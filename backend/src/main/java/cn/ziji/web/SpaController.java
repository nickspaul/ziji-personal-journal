package cn.ziji.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/dashboard","/records","/analytics","/statistics","/products","/catalog","/purchases","/manage","/settings"})
    public String spa(){return "forward:/index.html";}
}
