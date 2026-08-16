## Verification

### Linting

Run the formatter before testing:

```shell
./gradlew fmt
```

### Testing

Run the checks relevant to the changes:

- Kotlin changes: `./gradlew test`
- Android-specific changes: `./gradlew :android:assembleDebug`
- Web-specific changes: `./gradlew :web:wasmJsBrowserDevelopmentDistribution`
