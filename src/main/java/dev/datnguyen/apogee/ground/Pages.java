package dev.datnguyen.apogee.ground;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class Pages {
  @GetMapping({"/console", "/procedures", "/contacts", "/history"})
  public String page() {
    return "forward:/index.html";
  }
}
