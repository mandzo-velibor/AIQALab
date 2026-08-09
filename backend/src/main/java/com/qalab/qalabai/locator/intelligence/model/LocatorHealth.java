package com.qalab.qalabai.locator.intelligence.model;

/**
 * Health classification for a locator, combining its intrinsic stability and
 * its historical survival rate on the same element.
 *
 * <ul>
 *   <li>HEALTHY - stable strategy, high survival rate</li>
 *   <li>WARNING - moderately stable, or small history</li>
 *   <li>FRAGILE - positional/generated or failing history</li>
 *   <li>BROKEN - does not resolve, or very low stability</li>
 *   <li>UNKNOWN - no evidence available</li>
 * </ul>
 */
public enum LocatorHealth {
    HEALTHY,
    WARNING,
    FRAGILE,
    BROKEN,
    UNKNOWN
}
