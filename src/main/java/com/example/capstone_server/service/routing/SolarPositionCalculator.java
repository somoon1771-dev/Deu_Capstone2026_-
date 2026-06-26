package com.example.capstone_server.service.routing;

import com.example.capstone_server.dto.RoutePoint;

import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

@Component
public class SolarPositionCalculator {

    public SolarPosition calculate(RoutePoint point, ZonedDateTime time) {
        int dayOfYear = time.getDayOfYear();
        double hour = time.getHour() + time.getMinute() / 60.0 + time.getSecond() / 3600.0;
        double gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + (hour - 12.0) / 24.0);

        double equationOfTime = 229.18 * (
                0.000075
                        + 0.001868 * Math.cos(gamma)
                        - 0.032077 * Math.sin(gamma)
                        - 0.014615 * Math.cos(2.0 * gamma)
                        - 0.040849 * Math.sin(2.0 * gamma)
        );
        double declination = 0.006918
                - 0.399912 * Math.cos(gamma)
                + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2.0 * gamma)
                + 0.000907 * Math.sin(2.0 * gamma)
                - 0.002697 * Math.cos(3.0 * gamma)
                + 0.00148 * Math.sin(3.0 * gamma);

        double offsetMinutes = time.getOffset().getTotalSeconds() / 60.0;
        double trueSolarTime = (hour * 60.0 + equationOfTime + 4.0 * point.getLon() - offsetMinutes) % 1440.0;
        double hourAngleDegrees = trueSolarTime / 4.0 < 0
                ? trueSolarTime / 4.0 + 180.0
                : trueSolarTime / 4.0 - 180.0;

        double latitudeRadians = Math.toRadians(point.getLat());
        double hourAngleRadians = Math.toRadians(hourAngleDegrees);
        double cosZenith = Math.sin(latitudeRadians) * Math.sin(declination)
                + Math.cos(latitudeRadians) * Math.cos(declination) * Math.cos(hourAngleRadians);
        double zenithDegrees = Math.toDegrees(Math.acos(GeoMath.clamp(cosZenith, -1.0, 1.0)));
        double elevationDegrees = 90.0 - zenithDegrees;

        double azimuthRadians = Math.atan2(
                Math.sin(hourAngleRadians),
                Math.cos(hourAngleRadians) * Math.sin(latitudeRadians)
                        - Math.tan(declination) * Math.cos(latitudeRadians)
        );
        double azimuthDegrees = GeoMath.normalizeDegrees(Math.toDegrees(azimuthRadians) + 180.0);

        return new SolarPosition(elevationDegrees, azimuthDegrees);
    }
}
