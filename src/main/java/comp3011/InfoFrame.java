// stores frame information needed by the effects without making each effect query the decoder.
package comp3011;

public record InfoFrame(
        int frameNumber,
        long timestampUs) {
}
