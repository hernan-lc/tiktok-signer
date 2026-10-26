//! Resolve an observed numeric path without interpreting any captured values.
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let method = std::env::args()
        .nth(1)
        .ok_or("usage: schema-path METHOD NUMERIC.PATH")?;
    let path = std::env::args().nth(2).ok_or("missing numeric path")?;
    let mut schema = ttl_live_proto::schema_for_method(&method);
    let mut resolved = Vec::new();
    for number in path.split('.').map(str::parse::<u32>) {
        let number = number?;
        let field = schema.and_then(|schema| schema.field(number));
        resolved.push(serde_json::json!({
            "number": number, "parent_schema": schema.map(|s| s.name),
            "name": field.map(|f| f.name),
            "kind": field.map(|f| format!("{:?}", f.value_kind)),
        }));
        schema = field.and_then(|field| match field.kind {
            ttl_live_proto::FieldKind::Message(name) => ttl_live_proto::schema_by_name(name),
            _ => None,
        });
    }
    println!("{}", serde_json::to_string_pretty(&resolved)?);
    Ok(())
}
