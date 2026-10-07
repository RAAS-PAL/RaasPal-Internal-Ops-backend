package com.raaspal.robotrecommendation.casereport.customer;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The customer of each case on a pending tab, from the robot list. */
@RestController
@RequestMapping("/api/v1/case-reports/robot-customers")
@RequiredArgsConstructor
public class RobotCustomerController {

    /** More cases than every pending sheet of a day holds together, by far. */
    static final int MAX_CASES = 2000;

    private final RobotCustomers robots;

    /**
     * @param available false until the robot list has been read once: every case then keeps
     *                  its ticket's text
     * @param matches   each case that matched a robot, by the key the console sent
     */
    public record View(boolean available, Map<String, RobotCustomers.Match> matches) {
    }

    @PostMapping
    public ApiResponse<View> match(@RequestBody List<RobotCustomers.CaseRef> cases) {
        if (cases == null) cases = List.of();
        if (cases.size() > MAX_CASES) {
            throw new BadRequestException("Send at most " + MAX_CASES + " cases at a time.");
        }
        Map<String, RobotCustomers.Match> matches = robots.match(cases);
        return ApiResponse.success(new View(robots.available(), matches));
    }
}
